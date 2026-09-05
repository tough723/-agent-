package com.oncall.toolgateway;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * 基于 JDBC 的幂等账本 —— <b>多实例部署必须用这个</b>。
 *
 * <p>只用 JDK 自带的 {@code javax.sql} / {@code java.sql}，
 * 因此 {@code oncall-tool-gateway} 的生产代码仍然不引入 ORM 或连接池
 * （H2 只在测试作用域）。
 *
 * <p><b>抢占靠主键冲突，不靠"先查后写"</b>：
 * {@code INSERT} 撞上主键就说明别人已经抢到了。这是唯一在并发下成立的写法——
 * 应用层的 {@code SELECT} 再 {@code INSERT} 之间永远有窗口，
 * 而"两个实例同时扩容"正是这个窗口会造成真实事故的地方。
 *
 * <p><b>不用方言语法</b>：H2 的 {@code MERGE INTO} 与 PostgreSQL 的
 * {@code ON CONFLICT} 不通用，捕获重复键异常是两者都成立的写法。
 */
public final class JdbcToolExecutionLedger implements ToolExecutionLedger {

    /** 建表 DDL（跨方言）。生产环境应由 Flyway 管理，见 db/migration/V7。 */
    public static final String CREATE_TABLE_SQL = """
            CREATE TABLE IF NOT EXISTS %s (
                idempotency_key  VARCHAR(128)  NOT NULL PRIMARY KEY,
                tool_name        VARCHAR(191)  NOT NULL,
                state            VARCHAR(16)   NOT NULL,
                result           TEXT,
                created_at       TIMESTAMP     NOT NULL,
                updated_at       TIMESTAMP     NOT NULL
            )
            """;

    private final DataSource dataSource;
    private final String table;

    public JdbcToolExecutionLedger(DataSource dataSource) {
        this(dataSource, "tool_execution_claim");
    }

    public JdbcToolExecutionLedger(DataSource dataSource, String table) {
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
    public boolean claim(String idempotencyKey, String toolName) {
        String sql = "INSERT INTO " + table
                + " (idempotency_key, tool_name, state, result, created_at, updated_at)"
                + " VALUES (?,?,?,NULL,?,?)";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            Timestamp now = Timestamp.from(Instant.now());
            ps.setString(1, idempotencyKey);
            ps.setString(2, toolName);
            ps.setString(3, "CLAIMED");
            ps.setTimestamp(4, now);
            ps.setTimestamp(5, now);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            // 主键冲突 = 别人已经抢到执行权。这是正常分支，不是故障。
            // 其余 SQL 异常必须抛出：把连接故障当成"已被抢占"会让工具静默不执行，
            // 现象是"Agent 什么都不做"，比报错难查得多。
            if (isDuplicateKey(e)) {
                return false;
            }
            throw new IllegalStateException("抢占执行权失败：" + idempotencyKey, e);
        }
    }

    /**
     * 判断是否为唯一约束冲突。
     *
     * <p>刻意同时看 SQLState 与消息：H2 用 {@code 23505}，PostgreSQL 也是 {@code 23505}，
     * 但部分驱动会返回 {@code 23000} 大类，而消息里一定含 "Unique index or primary key violation"
     * 或 "duplicate key"。宁可多匹配，也不能把真冲突漏成"数据库故障"。
     */
    private static boolean isDuplicateKey(SQLException e) {
        String state = e.getSQLState();
        if (state != null && state.startsWith("23")) {
            return true;
        }
        String msg = String.valueOf(e.getMessage()).toLowerCase();
        return msg.contains("unique") || msg.contains("duplicate") || msg.contains("primary key");
    }

    @Override
    public boolean isCompleted(String idempotencyKey) {
        String sql = "SELECT state FROM " + table + " WHERE idempotency_key=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "COMPLETED".equals(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询幂等状态失败：" + idempotencyKey, e);
        }
    }

    @Override
    public String resultOf(String idempotencyKey) {
        String sql = "SELECT result FROM " + table + " WHERE idempotency_key=? AND state='COMPLETED'";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询执行结果失败：" + idempotencyKey, e);
        }
    }

    @Override
    public void complete(String idempotencyKey, String result) {
        String update = "UPDATE " + table + " SET state='COMPLETED', result=?, updated_at=?"
                + " WHERE idempotency_key=?";
        String insert = "INSERT INTO " + table
                + " (idempotency_key, tool_name, state, result, created_at, updated_at)"
                + " VALUES (?,?,'COMPLETED',?,?,?)";
        try (Connection c = dataSource.getConnection()) {
            Timestamp now = Timestamp.from(Instant.now());
            try (PreparedStatement ps = c.prepareStatement(update)) {
                ps.setString(1, result);
                ps.setTimestamp(2, now);
                ps.setString(3, idempotencyKey);
                if (ps.executeUpdate() > 0) {
                    return;
                }
            }
            // 走到这里说明抢占行已被清理任务当作崩溃残留删掉了。
            // 仍然要把结果写下来：否则这次执行成功了却不可重放，
            // 下一次重试会真的再执行一遍。
            try (PreparedStatement ps = c.prepareStatement(insert)) {
                ps.setString(1, idempotencyKey);
                ps.setString(2, "");
                ps.setString(3, result);
                ps.setTimestamp(4, now);
                ps.setTimestamp(5, now);
                ps.executeUpdate();
            } catch (SQLException e) {
                if (!isDuplicateKey(e)) {
                    throw e;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("写入执行结果失败：" + idempotencyKey, e);
        }
    }

    @Override
    public void release(String idempotencyKey) {
        // 只释放仍处于 CLAIMED 的行。
        // 少了这个条件的话，一次迟到的失败回调会删掉已经 COMPLETED 的行，
        // 让成功的执行结果丢失 —— 下次重试就真的会再执行一遍。
        String sql = "DELETE FROM " + table + " WHERE idempotency_key=? AND state='CLAIMED'";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, idempotencyKey);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("释放执行权失败：" + idempotencyKey, e);
        }
    }

    /** 崩溃残留清理：CLAIMED 且超过 {@code olderThanMillis} 未更新的行。 */
    public int releaseStale(long olderThanMillis) {
        String sql = "DELETE FROM " + table + " WHERE state='CLAIMED' AND updated_at < ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setTimestamp(1, Timestamp.from(Instant.now().minusMillis(olderThanMillis)));
            return ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("清理残留抢占失败", e);
        }
    }

    /** 账本行数，供指标与自检。 */
    public int size() {
        try (Connection c = dataSource.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            throw new IllegalStateException("统计账本失败", e);
        }
    }
}
