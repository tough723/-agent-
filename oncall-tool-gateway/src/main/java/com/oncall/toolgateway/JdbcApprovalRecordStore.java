package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 基于 JDBC 的审批记录存储 —— <b>生产必须用这个</b>。
 *
 * <p>只用 JDK 自带的 {@code javax.sql} / {@code java.sql}，与
 * {@link JdbcToolAuditLog} / {@link JdbcToolExecutionLedger} 一致：
 * 生产代码不引入 ORM 或连接池（H2 只在测试作用域）。
 *
 * <p><b>{@link #decide} 靠条件更新而不是先查后写</b>：
 * {@code UPDATE ... WHERE id=? AND decision='PENDING'}。
 * 两个审批人同时点批准时，只有一个的 {@code executeUpdate()} 返回 1，
 * 另一个返回 0 ——这是数据库能给的保证，应用层的
 * {@code SELECT} 再 {@code UPDATE} 之间永远有窗口。
 *
 * <p><b>不用方言语法</b>：纯 {@code INSERT} / {@code UPDATE} / {@code SELECT}，
 * H2 与 PostgreSQL 都成立。
 */
public final class JdbcApprovalRecordStore implements ApprovalRecordStore {

    /** 建表 DDL（跨方言）。生产由 Flyway 管理，见 {@code db/migration/V2}。 */
    public static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS %s (
                id             VARCHAR(64)  NOT NULL PRIMARY KEY,
                trace_id       VARCHAR(64)  NOT NULL,
                run_id         VARCHAR(64),
                step_id        VARCHAR(64),
                tool_name      VARCHAR(191) NOT NULL,
                risk_level     VARCHAR(32)  NOT NULL,
                requester      VARCHAR(64)  NOT NULL,
                args_snapshot  TEXT         NOT NULL,
                decision       VARCHAR(32)  NOT NULL,
                approver       VARCHAR(64),
                comment        VARCHAR(500),
                requested_at   TIMESTAMP    NOT NULL,
                decided_at     TIMESTAMP,
                CONSTRAINT chk_approval_not_self CHECK (approver IS NULL OR approver <> requester)
            )
            """;

    private static final String COLUMNS =
            "(id, trace_id, run_id, step_id, tool_name, risk_level, requester,"
                    + " args_snapshot, decision, approver, comment, requested_at, decided_at)";

    private static final String SELECT_COLUMNS =
            "id, trace_id, run_id, step_id, tool_name, risk_level, requester,"
                    + " args_snapshot, decision, approver, comment, requested_at, decided_at";

    private final DataSource dataSource;
    private final String table;

    public JdbcApprovalRecordStore(DataSource dataSource) {
        this(dataSource, "approval_record");
    }

    public JdbcApprovalRecordStore(DataSource dataSource, String table) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        this.dataSource = dataSource;
        this.table = table;
    }

    /** 建表（幂等）。测试与首次启动用；生产建议交给 Flyway。 */
    public void createSchemaIfMissing() {
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute(String.format(CREATE_TABLE_SQL, table));
        } catch (SQLException e) {
            throw new IllegalStateException("建表失败：" + table, e);
        }
    }

    @Override
    public void insert(ApprovalRecord r) {
        if (r == null) {
            throw new IllegalArgumentException("record 不能为 null");
        }
        String sql = "INSERT INTO " + table + " " + COLUMNS + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ToolAuditContext ctx = r.context();
            ps.setString(1, r.id());
            ps.setString(2, ctx.traceId());
            setNullable(ps, 3, ctx.runId());
            setNullable(ps, 4, ctx.stepId());
            ps.setString(5, r.toolName());
            ps.setString(6, r.riskLevel().name());
            ps.setString(7, r.requester());
            ps.setString(8, r.argsSnapshot());
            ps.setString(9, r.decision().name());
            setNullable(ps, 10, r.approver());
            setNullable(ps, 11, r.comment());
            ps.setTimestamp(12, Timestamp.from(r.requestedAt()));
            if (r.decidedAt() == null) {
                ps.setNull(13, Types.TIMESTAMP);
            } else {
                ps.setTimestamp(13, Timestamp.from(r.decidedAt()));
            }
            ps.executeUpdate();
        } catch (SQLException e) {
            // 主键冲突 = 同一次审批被提交了两次。这是调用方的错，不是数据库故障，
            // 所以单独映射成 IllegalArgumentException（与内存版语义一致）。
            if (isDuplicateKey(e)) {
                throw new IllegalArgumentException("审批记录 id 已存在：" + r.id(), e);
            }
            // 自批撞 CHECK 约束也走这里：不能吞掉，那是安全信号。
            throw new IllegalStateException("写入审批记录失败：" + r.id(), e);
        }
    }

    @Override
    public boolean decide(String id, ApprovalDecision outcome, String approver, String comment) {
        if (id == null || outcome == null || !outcome.isFinal()) {
            throw new IllegalArgumentException("id 与终态结论都不能为空，收到：" + outcome);
        }
        // 先用目标状态构造一条记录，让 ApprovalRecord 的不变量在这里就跑一遍。
        // 少了这一步，「TIMED_OUT 却带审批人」这类造假要等到读回来才发现。
        ApprovalRecord probe = new ApprovalRecord(id, ToolAuditContext.of("probe"), "probe",
                RiskLevel.READ_ONLY, "probe-requester", "{}", outcome, approver, comment,
                Instant.EPOCH, Instant.EPOCH);

        String sql = "UPDATE " + table
                + " SET decision=?, approver=?, comment=?, decided_at=?"
                + " WHERE id=? AND decision='PENDING'";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, outcome.name());
            setNullable(ps, 2, probe.approver());
            setNullable(ps, 3, probe.comment());
            ps.setTimestamp(4, Timestamp.from(probe.decidedAt()));
            ps.setString(5, id);
            // 返回 0 有两种可能：记录不存在，或已被别人决定。
            // 两种都意味着「本次调用没有完成这次决定」，调用方该做的是重读而不是重试。
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("更新审批结论失败：" + id, e);
        }
    }

    @Override
    public Optional<ApprovalRecord> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String sql = "SELECT " + SELECT_COLUMNS + " FROM " + table + " WHERE id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询审批记录失败：" + id, e);
        }
    }

    @Override
    public List<ApprovalRecord> pending() {
        String sql = "SELECT " + SELECT_COLUMNS + " FROM " + table
                + " WHERE decision='PENDING' ORDER BY requested_at, id";
        return query(sql, null);
    }

    @Override
    public List<ApprovalRecord> pendingRequestedBefore(Instant requestedBefore) {
        if (requestedBefore == null) {
            throw new IllegalArgumentException("requestedBefore 不能为 null");
        }
        String sql = "SELECT " + SELECT_COLUMNS + " FROM " + table
                + " WHERE decision='PENDING' AND requested_at < ? ORDER BY requested_at, id";
        return query(sql, requestedBefore);
    }

    private List<ApprovalRecord> query(String sql, Instant requestedBefore) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            if (requestedBefore != null) {
                ps.setTimestamp(1, Timestamp.from(requestedBefore));
            }
            try (ResultSet rs = ps.executeQuery()) {
                List<ApprovalRecord> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(map(rs));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询审批记录失败", e);
        }
    }

    @Override
    public int count() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("统计审批记录失败", e);
        }
    }

    private static ApprovalRecord map(ResultSet rs) throws SQLException {
        Timestamp decided = rs.getTimestamp(13);
        return new ApprovalRecord(
                rs.getString(1),
                new ToolAuditContext(rs.getString(2), rs.getString(3), rs.getString(4), null),
                rs.getString(5),
                RiskLevel.valueOf(rs.getString(6)),
                rs.getString(7),
                rs.getString(8),
                ApprovalDecision.valueOf(rs.getString(9)),
                rs.getString(10),
                rs.getString(11),
                rs.getTimestamp(12).toInstant(),
                decided == null ? null : decided.toInstant());
    }

    private static boolean isDuplicateKey(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        return msg.contains("unique") || msg.contains("duplicate") || msg.contains("primary key");
    }

    private static void setNullable(PreparedStatement ps, int idx, String value) throws SQLException {
        if (value == null) {
            ps.setNull(idx, Types.VARCHAR);
        } else {
            ps.setString(idx, value);
        }
    }
}
