package com.oncall.alert;

import com.oncall.domain.alert.AlertEvent;
import com.oncall.domain.alert.AlertGroup;
import com.oncall.domain.alert.AlertStatus;
import com.oncall.domain.autonomy.AlertSeverity;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link AlertStore} 的 JDBC 实现。纯 {@code javax.sql}，与既有 {@code Jdbc*} 同路子。
 *
 * <p><b>刻意不持有 {@code CREATE_TABLE} 常量</b>：两张告警表的 DDL
 * 只存在于 {@code db/migration/V3__alert.sql}，测试直接读原文建表。
 *
 * <h2>★ {@link #ingest} 的事务边界</h2>
 * <p>插入事件与递增计数必须在<b>同一个事务</b>里，且<b>显式关闭自动提交</b>。
 * 这不是风格问题：{@code alert_event.group_id} 没有外键（分区表不支持跨分区外键），
 * 所以数据库不会替我们保证 {@code event_count} 与真实行数一致。
 * 若分成两次提交，中间崩一次计数就永久少 1，
 * 而聚合率（第一成本杠杆）会安静地偏高。
 *
 * <p>失败路径必须 {@code rollback} 而不是仅仅让异常穿出去：
 * 连接归还连接池时若仍带着未提交的事务，
 * 下一个借用者可能把<b>别人的</b>半截写入一起提交掉。
 */
public final class JdbcAlertStore implements AlertStore {

    /**
     * PostgreSQL 的 unique_violation。用 SQLSTATE 而不是匹配异常消息：
     * 消息是驱动实现细节，换版本或换语言环境就变，SQLSTATE 是标准。
     */
    private static final String SQLSTATE_UNIQUE_VIOLATION = "23505";
    /** foreign_key_violation。分区表没有外键，所以这条只可能来自别处——留作诊断。 */
    private static final String SQLSTATE_FK_VIOLATION = "23503";

    private static final String INSERT_GROUP_SQL = """
            INSERT INTO alert_group (
                id, fingerprint, service, severity, status,
                event_count, first_seen_at, last_seen_at, run_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String SELECT_GROUP_SQL = """
            SELECT id, fingerprint, service, severity, status,
                   event_count, first_seen_at, last_seen_at, run_id
              FROM alert_group
             WHERE id = ? AND first_seen_at = ?
            """;

    private static final String INSERT_EVENT_SQL = """
            INSERT INTO alert_event (
                id, group_id, source, raw_payload, labels,
                severity, fired_at, received_at
            ) VALUES (?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?)
            """;

    /**
     * 递增计数并推进「最后出现」。
     *
     * <p>用 {@code event_count + 1} 而不是把 Java 侧算好的值写回去：
     * 并发接入同一个组时，读-改-写会丢更新（两个 worker 都读到 3、都写 4），
     * 而 {@code +1} 由数据库在同一行上串行执行，不会丢。
     */
    private static final String BUMP_GROUP_SQL = """
            UPDATE alert_group
               SET event_count = event_count + 1,
                   last_seen_at = ?
             WHERE id = ? AND first_seen_at = ?
            """;

    private static final String UPDATE_STATUS_SQL = """
            UPDATE alert_group
               SET status = ?
             WHERE id = ? AND first_seen_at = ?
            """;

    private static final String COUNT_EVENTS_SQL = """
            SELECT COUNT(*) FROM alert_event WHERE group_id = ?
            """;

    private final DataSource dataSource;

    public JdbcAlertStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public void insertGroup(AlertGroup group) {
        Objects.requireNonNull(group, "group");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(INSERT_GROUP_SQL)) {
            ps.setString(1, group.id());
            ps.setString(2, group.fingerprint());
            ps.setString(3, group.service());
            ps.setString(4, group.severity().name());
            ps.setString(5, group.status().name());
            ps.setInt(6, group.eventCount());
            ps.setTimestamp(7, Timestamp.from(group.firstSeenAt()));
            ps.setTimestamp(8, Timestamp.from(group.lastSeenAt()));
            ps.setString(9, group.runId());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "写入 alert_group 失败，id=" + group.id() + " SQLSTATE=" + e.getSQLState(), e);
        }
    }

    @Override
    public Optional<AlertGroup> findGroup(String id, Instant firstSeenAt) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(SELECT_GROUP_SQL)) {
            ps.setString(1, id);
            ps.setTimestamp(2, Timestamp.from(firstSeenAt));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(mapGroup(rs)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询 alert_group 失败，id=" + id, e);
        }
    }

    @Override
    public boolean ingest(AlertGroup group, AlertEvent event) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(event, "event");
        if (!group.id().equals(event.groupId())) {
            // 没有外键，所以这条一致性只能在这里守。
            throw new IllegalArgumentException("事件声称属于组 " + event.groupId()
                    + "，但传入的组是 " + group.id() + "——alert_event.group_id 没有外键，"
                    + "这条一致性只能由调用方与本法共同保证");
        }

        Connection c = null;
        try {
            c = dataSource.getConnection();
            // ★ 显式关闭自动提交：两条语句必须一起成功或一起消失。
            c.setAutoCommit(false);

            boolean inserted;
            try (PreparedStatement ps = c.prepareStatement(INSERT_EVENT_SQL)) {
                ps.setString(1, event.id());
                ps.setString(2, event.groupId());
                ps.setString(3, event.source());
                ps.setString(4, event.rawPayload());
                if (event.labels() == null) {
                    ps.setNull(5, Types.OTHER);
                } else {
                    ps.setString(5, event.labels());
                }
                ps.setString(6, event.severity().name());
                ps.setTimestamp(7, Timestamp.from(event.firedAt()));
                ps.setTimestamp(8, Timestamp.from(event.receivedAt()));
                ps.executeUpdate();
                inserted = true;
            } catch (SQLException e) {
                if (SQLSTATE_UNIQUE_VIOLATION.equals(e.getSQLState())) {
                    // 同一条告警被重复投递：回滚（其实什么都没写），并且不递增计数。
                    c.rollback();
                    return false;
                }
                throw e;
            }

            if (inserted) {
                try (PreparedStatement ps = c.prepareStatement(BUMP_GROUP_SQL)) {
                    ps.setTimestamp(1, Timestamp.from(event.firedAt()));
                    ps.setString(2, group.id());
                    ps.setTimestamp(3, Timestamp.from(group.firstSeenAt()));
                    int n = ps.executeUpdate();
                    if (n == 0) {
                        // 组不存在。没有外键，所以数据库不会替我们拦——必须自己抛并回滚，
                        // 否则会留下一条挂在不存在的组上的孤儿事件。
                        throw new SQLException("组不存在，事件将成孤儿：groupId=" + group.id(),
                                SQLSTATE_FK_VIOLATION);
                    }
                }
            }

            c.commit();
            return true;
        } catch (SQLException e) {
            rollbackQuietly(c);
            throw new IllegalStateException("接入告警事件失败，eventId=" + event.id()
                    + " SQLSTATE=" + e.getSQLState() + "：" + e.getMessage(), e);
        } finally {
            closeQuietly(c);
        }
    }

    @Override
    public int countEventsInGroup(String groupId) {
        Objects.requireNonNull(groupId, "groupId");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(COUNT_EVENTS_SQL)) {
            ps.setString(1, groupId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("统计 alert_event 失败，groupId=" + groupId, e);
        }
    }

    @Override
    public void updateGroupStatus(AlertGroup group) {
        Objects.requireNonNull(group, "group");
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(UPDATE_STATUS_SQL)) {
            ps.setString(1, group.status().name());
            ps.setString(2, group.id());
            ps.setTimestamp(3, Timestamp.from(group.firstSeenAt()));
            int n = ps.executeUpdate();
            if (n == 0) {
                throw new IllegalStateException(
                        "更新 alert_group 状态命中 0 行，id=" + group.id());
            }
        } catch (SQLException e) {
            throw new IllegalStateException("更新 alert_group 状态失败，id=" + group.id(), e);
        }
    }

    /**
     * 回滚失败时不能再抛异常盖掉原始异常——那会让人去查错误的原因。
     * 原始异常才是「为什么这次接入失败」的答案。
     */
    private static void rollbackQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            c.rollback();
        } catch (SQLException ignored) {
            // 刻意吞掉：原始异常更有诊断价值。
        }
    }

    private static void closeQuietly(Connection c) {
        if (c == null) {
            return;
        }
        try {
            // 必须归还自动提交，否则连接池里的这条连接会带着
            // 「不自动提交」的状态被下一个借用者拿到。
            c.setAutoCommit(true);
            c.close();
        } catch (SQLException ignored) {
            // 关闭失败没有可执行的补救动作。
        }
    }

    private static AlertGroup mapGroup(ResultSet rs) throws SQLException {
        return new AlertGroup(
                rs.getString("id"),
                rs.getString("fingerprint"),
                rs.getString("service"),
                AlertSeverity.valueOf(rs.getString("severity")),
                AlertStatus.valueOf(rs.getString("status")),
                rs.getInt("event_count"),
                rs.getTimestamp("first_seen_at").toInstant(),
                rs.getTimestamp("last_seen_at").toInstant(),
                rs.getString("run_id"));
    }
}
