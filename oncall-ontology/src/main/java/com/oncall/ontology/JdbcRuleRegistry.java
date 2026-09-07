package com.oncall.ontology;

import com.oncall.ontology.rule.OntologyRule;
import com.oncall.ontology.rule.RuleRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * {@link RuleRegistry} 的 JDBC 实现，对应 {@code onto_rule} 表。
 *
 * <h2>★ 为什么 {@code setEnabled} 对未知 id 抛异常，而 {@code recordHits} 静默忽略</h2>
 * <p>两者面对的是同一个「id 不认识」的情形，但后果相反：
 *
 * <ul>
 *   <li><b>{@code setEnabled} 写错 id ⇒ 安全规则没被关掉，而运维以为关了。</b>
 *       这是会出事故的静默失效，必须在写入的那一刻就炸。
 *       {@link com.oncall.ontology.rule.RuleEngine} 只能在求值时发警告，
 *       那时决定已经做完了 —— 拦不住。</li>
 *   <li><b>{@code recordHits} 写错 id ⇒ 少统计一次命中。</b>
 *       这是观测数据的损失，不该让一次统计失败把已经算出来的
 *       安全结论（几人审批、放权上限）一起带崩。</li>
 * </ul>
 *
 * <h2>★ 为什么 {@code syncKnownRules} 绝不碰 {@code enabled}</h2>
 * <p>启动时重新同步是常态。如果同步会把 {@code enabled} 重置成默认值 TRUE，
 * 那么运维刻意停用的规则会在<b>每次重启后自己复活</b> ——
 * 这比「停用不生效」更危险，因为它看起来生效过一段时间。
 * 所以同步只补 {@code id} 与 {@code description}，开关一律不动。
 *
 * <h2>★ 为什么 {@code recordHits} 不刷新 {@code updated_at}</h2>
 * <p>{@code updated_at} 的语义是「开关最后一次被人改动的时间」。
 * 每次命中都刷新它，就等于把「这条规则是三个月前被停用的」这个信息冲掉 ——
 * 而那正是复盘时最需要知道的事。
 */
public final class JdbcRuleRegistry implements RuleRegistry {

    public JdbcRuleRegistry(DataSource dataSource) {
        this(dataSource, "onto_rule");
    }

    /** @param table 表名，测试里可指向临时表 */
    public JdbcRuleRegistry(DataSource dataSource, String table) {
        if (dataSource == null) {
            throw new IllegalArgumentException("dataSource 不能为 null");
        }
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("table 不能为空");
        }
        this.dataSource = dataSource;
        this.table = table;
    }

    private final DataSource dataSource;
    private final String table;

    /**
     * 建表（若不存在）。与 {@link JdbcOntologyStore#createSchemaIfMissing()} 同形。
     *
     * <p>列定义与 {@code V6__ontology.sql} 里的 {@code onto_rule} 保持一致。
     * 生产环境由迁移脚本建表，这里存在是为了让测试能跑在真实 H2 上 ——
     * SQL 写错了用假 {@code Connection} 是测不出来的。
     */
    public void createSchemaIfMissing() {
        String ddl = "CREATE TABLE IF NOT EXISTS " + table + " ("
                + "id VARCHAR(64) NOT NULL PRIMARY KEY,"
                + "enabled BOOLEAN NOT NULL DEFAULT TRUE,"
                + "description VARCHAR(500) NOT NULL,"
                + "hit_count BIGINT NOT NULL DEFAULT 0,"
                + "updated_at TIMESTAMP NOT NULL)";
        try (Connection c = dataSource.getConnection();
             java.sql.Statement st = c.createStatement()) {
            st.execute(ddl);
        } catch (SQLException e) {
            throw new IllegalStateException("建表失败：" + table, e);
        }
    }

    @Override
    public Set<String> disabledRuleIds() {
        String sql = "SELECT id FROM " + table + " WHERE enabled = FALSE";
        // LinkedHashSet：保持库里返回的顺序，便于日志与断言稳定。
        Set<String> out = new LinkedHashSet<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取停用的规则失败", e);
        }
        return out;
    }

    @Override
    public void setEnabled(String ruleId, boolean enabled) {
        Objects.requireNonNull(ruleId, "ruleId");
        String sql = "UPDATE " + table + " SET enabled = ?, updated_at = ? WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, enabled);
            ps.setTimestamp(2, Timestamp.from(Instant.now()));
            ps.setString(3, ruleId);
            if (ps.executeUpdate() == 0) {
                // ★ 不自动建行。自动建行等于接受一个可能是拼写错误的 id，
                //   而后果是「以为关掉了，其实没关」——安全规则照常在跑。
                throw new IllegalArgumentException("onto_rule 里没有 id=" + ruleId
                        + " 这一行，拒绝写入。可能是拼写错误，也可能是这条规则还没同步进表——"
                        + "先调用 syncKnownRules 再改开关。"
                        + "（自动建行会让一个拼错的 id 看起来「停用成功」，"
                        + "而真正那条规则照常在跑）");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("更新规则开关失败：" + ruleId, e);
        }
    }

    @Override
    public void recordHits(Collection<String> firedRuleIds) {
        Objects.requireNonNull(firedRuleIds, "firedRuleIds");
        if (firedRuleIds.isEmpty()) {
            return;
        }
        // 刻意不带 updated_at：见类注释第 3 条。
        String sql = "UPDATE " + table + " SET hit_count = hit_count + 1 WHERE id = ?";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            for (String id : firedRuleIds) {
                if (id == null) {
                    continue;
                }
                ps.setString(1, id);
                // 影响行数为 0 说明这个 id 不在表里：静默忽略，见类注释第 1 条。
                ps.addBatch();
            }
            ps.executeBatch();
        } catch (SQLException e) {
            // 观测数据写失败不该冒泡到安全判定链路，但也不能完全无声。
            throw new IllegalStateException("累加规则命中次数失败", e);
        }
    }

    @Override
    public void syncKnownRules(List<OntologyRule> rules) {
        Objects.requireNonNull(rules, "rules");
        // 与 JdbcOntologyStore 同样不用方言的 ON CONFLICT：
        // 先 UPDATE，按影响行数决定要不要 INSERT。H2 与 PostgreSQL 都成立。
        String update = "UPDATE " + table + " SET description = ? WHERE id = ?";
        String insert = "INSERT INTO " + table
                + " (id, enabled, description, hit_count, updated_at) VALUES (?,?,?,?,?)";
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                for (OntologyRule rule : rules) {
                    try (PreparedStatement ps = c.prepareStatement(update)) {
                        ps.setString(1, rule.description());
                        ps.setString(2, rule.id());
                        if (ps.executeUpdate() == 0) {
                            try (PreparedStatement ins = c.prepareStatement(insert)) {
                                ins.setString(1, rule.id());
                                // 新同步进来的规则默认启用：安全默认值是「生效」。
                                ins.setBoolean(2, true);
                                ins.setString(3, rule.description());
                                ins.setLong(4, 0L);
                                ins.setTimestamp(5, Timestamp.from(Instant.now()));
                                ins.executeUpdate();
                            }
                        }
                        // 注意：UPDATE 分支刻意不写 enabled ——
                        // 否则运维停用的规则会在每次重启同步后自己复活。
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("同步规则注册表失败", e);
        }
    }
}
