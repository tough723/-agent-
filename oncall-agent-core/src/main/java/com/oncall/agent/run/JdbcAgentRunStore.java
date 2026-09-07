package com.oncall.agent.run;

import com.oncall.domain.autonomy.AutonomyLevel;
import com.oncall.domain.run.AgentRun;
import com.oncall.domain.run.RunStatus;
import com.oncall.domain.trace.TraceId;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link AgentRunStore} 的 JDBC 实现。纯 {@code javax.sql}，不引 ORM——
 * 与 {@code JdbcToolAuditLog} / {@code JdbcApprovalRecordStore} 同一路子。
 *
 * <p><b>刻意不持有 {@code CREATE_TABLE} 常量</b>：{@code agent_run} 的 DDL
 * 只存在于 {@code db/migration/V2__agent_execution.sql}。Java 里复制一份，
 * 两份就会分叉，而分叉的 DDL 是静默的。测试直接读迁移脚本原文建表。
 */
public final class JdbcAgentRunStore implements AgentRunStore {

    /**
     * 16 列：V2 的 14 列 + V9 加的 {@code budget_replans} / {@code used_replans}。
     * {@code id} 是应用侧生成的 VARCHAR(64)，不是 identity，所以<b>要</b>出现在 INSERT 里。
     */
    private static final String INSERT_SQL = """
            INSERT INTO agent_run (
                id, trace_id, alert_group_id, status, autonomy_level, step_cursor,
                budget_steps, budget_tokens, budget_cost, budget_replans,
                used_steps, used_tokens, used_cost, used_replans,
                created_at, finished_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * ★ 只有八个占位符（七列进度 + {@code id}），因为这是<b>进度</b>更新。
     *
     * <p>{@code autonomy_level} 刻意不在其中：它是放权等级快照，
     * 写进去就会被当前配置覆盖掉当时的授权，且不报错。
     * {@code trace_id} / 四项预算 / {@code created_at} 同理——它们不该在跑动中变。
     * 少写这些列不是省事，是让「快照被覆盖」这件事在语法上就做不到。
     *
     * <p><b>{@code used_replans} 在其中，而 {@code budget_replans} 不在</b>：
     * 前者是进度（每重规划一次就要落库，否则 worker 崩溃重启后
     * 重规划次数归零，「重规划预算耗尽」这个终止条件就失效了），
     * 后者是预算（写入一次）。这条区分与 {@code used_steps} / {@code budget_steps} 一致。
     */
    private static final String UPDATE_SQL = """
            UPDATE agent_run
               SET status = ?, step_cursor = ?,
                   used_steps = ?, used_tokens = ?, used_cost = ?, used_replans = ?,
                   finished_at = ?
             WHERE id = ?
            """;

    private static final String SELECT_SQL = """
            SELECT id, trace_id, alert_group_id, status, autonomy_level, step_cursor,
                   budget_steps, budget_tokens, budget_cost, budget_replans,
                   used_steps, used_tokens, used_cost, used_replans,
                   created_at, finished_at
              FROM agent_run
             WHERE id = ?
            """;

    private final DataSource dataSource;

    public JdbcAgentRunStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void insert(AgentRun run) {
        Objects.requireNonNull(run, "run");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_SQL)) {
            bind(ps, run);
            ps.executeUpdate();
        } catch (SQLException e) {
            // 不吞：主键冲突意味着 id 生成有问题，静默丢弃会让一次排查凭空消失。
            throw new IllegalStateException("写入 agent_run 失败，id=" + run.id(), e);
        }
    }

    @Override
    public void update(AgentRun run) {
        Objects.requireNonNull(run, "run");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(UPDATE_SQL)) {
            ps.setString(1, run.status().name());
            ps.setInt(2, run.stepCursor());
            ps.setInt(3, run.usedSteps());
            ps.setLong(4, run.usedTokens());
            ps.setBigDecimal(5, run.usedCost());
            // used_replans 是进度，必须落库：worker 崩溃重启后若它归零，
            // 「重规划预算耗尽」这个终止条件就失效了，循环可以无限改主意。
            ps.setInt(6, run.usedReplans());
            ps.setTimestamp(7, run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()));
            ps.setString(8, run.id());
            int n = ps.executeUpdate();
            if (n == 0) {
                // 更新到 0 行说明这次 run 根本没被 insert 过，或 id 拼错了。
                // 静默返回会让「进度推进了」变成一句假话。
                throw new IllegalStateException("更新 agent_run 命中 0 行，id=" + run.id());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("更新 agent_run 失败，id=" + run.id(), e);
        }
    }

    @Override
    public Optional<AgentRun> findById(String id) {
        Objects.requireNonNull(id, "id");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_SQL)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 agent_run 失败，id=" + id, e);
        }
    }

    private static void bind(PreparedStatement ps, AgentRun run) throws SQLException {
        ps.setString(1, run.id());
        ps.setString(2, run.traceId().value());
        ps.setString(3, run.alertGroupId());
        ps.setString(4, run.status().name());
        ps.setString(5, run.autonomyLevel().name());
        ps.setInt(6, run.stepCursor());
        ps.setInt(7, run.budgetSteps());
        ps.setLong(8, run.budgetTokens());
        ps.setBigDecimal(9, run.budgetCost());
        ps.setInt(10, run.budgetReplans());
        ps.setInt(11, run.usedSteps());
        ps.setLong(12, run.usedTokens());
        ps.setBigDecimal(13, run.usedCost());
        ps.setInt(14, run.usedReplans());
        ps.setTimestamp(15, Timestamp.from(run.createdAt()));
        ps.setTimestamp(16, run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()));
    }

    private static AgentRun map(ResultSet rs) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new AgentRun(
                rs.getString("id"),
                TraceId.adopt(rs.getString("trace_id")),
                rs.getString("alert_group_id"),
                RunStatus.valueOf(rs.getString("status")),
                AutonomyLevel.valueOf(rs.getString("autonomy_level")),
                rs.getInt("step_cursor"),
                rs.getInt("budget_steps"),
                rs.getLong("budget_tokens"),
                rs.getBigDecimal("budget_cost"),
                rs.getInt("budget_replans"),
                rs.getInt("used_steps"),
                rs.getLong("used_tokens"),
                rs.getBigDecimal("used_cost"),
                rs.getInt("used_replans"),
                rs.getTimestamp("created_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }
}
