package com.oncall.agent.run;

import com.oncall.domain.run.AgentStep;
import com.oncall.domain.run.StepStatus;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link AgentStepStore} 的 JDBC 实现。纯 {@code javax.sql}，与既有 {@code Jdbc*} 同路子。
 *
 * <p><b>刻意不持有 {@code CREATE_TABLE} 常量</b>：{@code agent_step} 的 DDL
 * 只存在于 {@code db/migration/V2__agent_execution.sql}，测试直接读原文建表。
 * Java 里复制一份，两份就会分叉，而分叉的 DDL 是静默的。
 */
public final class JdbcAgentStepStore implements AgentStepStore {

    /**
     * PostgreSQL 的 unique_violation SQLSTATE。
     *
     * <p>用 SQLSTATE 判断而不是匹配异常消息文本：消息是驱动的实现细节，
     * 换驱动版本或换语言环境就会变，而 SQLSTATE 是标准。
     */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";

    /** 11 列，顺序与 V2 的建表语句一致。{@code id} 由应用侧生成，不是 identity。 */
    private static final String INSERT_SQL = """
            INSERT INTO agent_step (
                id, run_id, seq, tool_name, args_json, idempotency_key,
                status, result_summary, error_message, started_at, finished_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * 只有四个占位符：这是<b>收尾</b>更新。
     * {@code idempotency_key}、{@code run_id}、{@code seq}、{@code started_at}
     * 都是写入一次的列——尤其幂等键，改了它等于把「这一步做过没有」的答案换掉。
     */
    private static final String FINISH_SQL = """
            UPDATE agent_step
               SET status = ?, result_summary = ?, error_message = ?, finished_at = ?
             WHERE id = ?
            """;

    private static final String SELECT_BY_ID_SQL = """
            SELECT id, run_id, seq, tool_name, args_json, idempotency_key,
                   status, result_summary, error_message, started_at, finished_at
              FROM agent_step
             WHERE id = ?
            """;

    /** 排序键就是 V2 建的 idx_agent_step_run (run_id, seq)。 */
    private static final String SELECT_BY_RUN_SQL = """
            SELECT id, run_id, seq, tool_name, args_json, idempotency_key,
                   status, result_summary, error_message, started_at, finished_at
              FROM agent_step
             WHERE run_id = ?
             ORDER BY seq ASC
            """;

    private final DataSource dataSource;

    public JdbcAgentStepStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public boolean tryInsert(AgentStep step) {
        Objects.requireNonNull(step, "step");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            bind(ps, step);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            // ★ 抢占失败是预期行为，必须与真正的故障分开。
            if (SQLSTATE_UNIQUE_VIOLATION.equals(e.getSQLState())) {
                return false;
            }
            throw new IllegalStateException(
                    "写入 agent_step 失败（非幂等冲突），id=" + step.id()
                            + " SQLSTATE=" + e.getSQLState(), e);
        }
    }

    @Override
    public void finish(AgentStep step) {
        Objects.requireNonNull(step, "step");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(FINISH_SQL)) {
            ps.setString(1, step.status().name());
            ps.setString(2, step.resultSummary());
            ps.setString(3, step.errorMessage());
            ps.setTimestamp(4, step.finishedAt() == null ? null : Timestamp.from(step.finishedAt()));
            ps.setString(5, step.id());
            int n = ps.executeUpdate();
            if (n == 0) {
                // 命中 0 行说明这一步从未被抢占成功，或 id 拼错了。
                // 静默返回会让「这一步收尾了」变成一句假话。
                throw new IllegalStateException("收尾 agent_step 命中 0 行，id=" + step.id());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("收尾 agent_step 失败，id=" + step.id(), e);
        }
    }

    @Override
    public Optional<AgentStep> findById(String id) {
        Objects.requireNonNull(id, "id");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_BY_ID_SQL)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 agent_step 失败，id=" + id, e);
        }
    }

    @Override
    public List<AgentStep> findByRunId(String runId) {
        Objects.requireNonNull(runId, "runId");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_BY_RUN_SQL)) {
            ps.setString(1, runId);
            List<AgentStep> out = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(map(rs));
                }
            }
            return List.copyOf(out);
        } catch (SQLException e) {
            throw new IllegalStateException("查询 agent_step 失败，runId=" + runId, e);
        }
    }

    private static void bind(PreparedStatement ps, AgentStep s) throws SQLException {
        ps.setString(1, s.id());
        ps.setString(2, s.runId());
        ps.setInt(3, s.seq());
        ps.setString(4, s.toolName());
        ps.setString(5, s.argsJson());
        ps.setString(6, s.idempotencyKey());
        ps.setString(7, s.status().name());
        ps.setString(8, s.resultSummary());
        ps.setString(9, s.errorMessage());
        ps.setTimestamp(10, Timestamp.from(s.startedAt()));
        ps.setTimestamp(11, s.finishedAt() == null ? null : Timestamp.from(s.finishedAt()));
    }

    private static AgentStep map(ResultSet rs) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new AgentStep(
                rs.getString("id"),
                rs.getString("run_id"),
                rs.getInt("seq"),
                rs.getString("tool_name"),
                rs.getString("args_json"),
                rs.getString("idempotency_key"),
                StepStatus.valueOf(rs.getString("status")),
                rs.getString("result_summary"),
                rs.getString("error_message"),
                rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }
}
