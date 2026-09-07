package com.oncall.ontology;

import com.oncall.ontology.rule.CriticalServiceNeedsTwoApprovals;
import com.oncall.ontology.rule.IrreversibleNeedsTwoApprovals;
import com.oncall.ontology.rule.OntologyRule;
import com.oncall.ontology.rule.ResourceAlertOnCriticalServiceCapsAutonomy;
import com.oncall.ontology.rule.RuleEngine;
import com.oncall.ontology.rule.StaleRunbookMustBeFlagged;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JdbcRuleRegistry} 的测试，跑在真实 H2 上。
 *
 * <p><b>为什么必须用真数据库</b>：理由与 {@code JdbcOntologyStoreTest} 相同 ——
 * SQL 写错了用假 {@code Connection} 是测不出来的，假对象只会返回你预设的值，
 * 等于把断言写在被测代码里。这里真正跑通的是 {@code UPDATE ... WHERE id=?}
 * 的影响行数判定，而<b>「影响行数为 0」正是拒绝未知 id 的唯一依据</b>。
 *
 * <p>★ 本类最关键的两条是
 * {@link #unknownRuleIdIsRejectedInsteadOfSilentlyInserted()} 与
 * {@link #syncDoesNotResurrectADeliberatelyDisabledRule()} ——
 * 它们分别堵住「以为关掉了其实没关」和「重启后自己复活」这两个事故。
 */
class JdbcRuleRegistryTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private DataSource dataSource;
    private JdbcRuleRegistry registry;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        // 每个测试一个独立库：开关状态会互相干扰，共用库会让测试顺序变成隐藏依赖。
        ds.setURL("jdbc:h2:mem:rule" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource = ds;
        registry = new JdbcRuleRegistry(dataSource);
        registry.createSchemaIfMissing();
    }

    /** 引擎默认装载的那四条。 */
    private static List<OntologyRule> allRules() {
        return List.of(
                new IrreversibleNeedsTwoApprovals(),
                new CriticalServiceNeedsTwoApprovals(),
                new ResourceAlertOnCriticalServiceCapsAutonomy(),
                new StaleRunbookMustBeFlagged());
    }

    private long hitCount(String id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT hit_count FROM onto_rule WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : -1L;
            }
        }
    }

    private Timestamp updatedAt(String id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT updated_at FROM onto_rule WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getTimestamp(1) : null;
            }
        }
    }

    private boolean rowExists(String id) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM onto_rule WHERE id=?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    // ------------------------------------------------------------- 建表与同步

    @Test
    @DisplayName("建表幂等：重复调用不报错")
    void createSchemaIsIdempotent() {
        registry.createSchemaIfMissing();
        registry.createSchemaIfMissing();
        assertTrue(registry.disabledRuleIds().isEmpty());
    }

    @Test
    @DisplayName("同步把四条规则写进表，默认全部启用")
    void syncInsertsAllRulesEnabledByDefault() throws SQLException {
        registry.syncKnownRules(allRules());

        // ★ 默认启用是安全默认值：这四条规则全都是收紧约束的
        //   （两人审批、放权上限）。缺行=启用，而不是缺行=停用。
        assertTrue(registry.disabledRuleIds().isEmpty(),
                "刚同步进来的规则必须默认启用");
        for (OntologyRule r : allRules()) {
            assertTrue(rowExists(r.id()), "规则 " + r.id() + " 应已入表");
            assertEquals(0L, hitCount(r.id()));
        }
    }

    @Test
    @DisplayName("同步是幂等的：重复调用不会重置 hit_count")
    void syncIsIdempotentAndKeepsHitCount() throws SQLException {
        registry.syncKnownRules(allRules());
        registry.recordHits(List.of("R1", "R1", "R1"));
        assertEquals(3L, hitCount("R1"));

        registry.syncKnownRules(allRules());   // 重启时的重新同步
        assertEquals(3L, hitCount("R1"), "重新同步不得清零命中统计");
    }

    // --------------------------------------------------------------- 开关语义

    /**
     * ★ 本类最关键的一条之一。
     *
     * <p>{@link RuleEngine} 只能在求值时对未知 id 发一条警告，而那时决定已经做完了。
     * 真正的后果是具体的：<b>运维以为自己关掉了某条规则，而真正那条规则照常在跑</b> ——
     * 静默失效，且没有任何报错。所以必须在写入的那一刻就拒绝。
     */
    @Test
    @DisplayName("★ 未知规则 id 在写入时就被拒绝，且不自动建行")
    void unknownRuleIdIsRejectedInsteadOfSilentlyInserted() throws SQLException {
        registry.syncKnownRules(allRules());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> registry.setEnabled("R9", false));
        assertTrue(e.getMessage().contains("R9"));

        // ★ 关键：拒绝之后表里不能多出一行。
        //   自动建行会让一个拼错的 id 看起来「停用成功」。
        assertFalse(rowExists("R9"), "被拒绝的 id 不得被写进表");
        assertTrue(registry.disabledRuleIds().isEmpty());
    }

    @Test
    @DisplayName("停用一条规则后，disabledRuleIds 只返回它")
    void disablingARuleShowsUpInDisabledIds() {
        registry.syncKnownRules(allRules());

        registry.setEnabled("R2", false);

        assertEquals(Set.of("R2"), registry.disabledRuleIds());
    }

    @Test
    @DisplayName("重新启用后从停用集合里消失")
    void reenablingRemovesItFromDisabledIds() {
        registry.syncKnownRules(allRules());
        registry.setEnabled("R2", false);
        registry.setEnabled("R2", true);

        assertTrue(registry.disabledRuleIds().isEmpty());
    }

    /**
     * ★ 本类最关键的另一条。
     *
     * <p>启动时重新同步是常态。如果同步会把 {@code enabled} 重置成默认值 TRUE，
     * 那么运维刻意停用的规则会在<b>每次重启后自己复活</b> ——
     * 这比「停用不生效」更危险，因为它看起来生效过一段时间。
     */
    @Test
    @DisplayName("★ 重新同步不会让刻意停用的规则复活")
    void syncDoesNotResurrectADeliberatelyDisabledRule() {
        registry.syncKnownRules(allRules());
        registry.setEnabled("R3", false);
        assertEquals(Set.of("R3"), registry.disabledRuleIds());

        registry.syncKnownRules(allRules());   // 模拟重启后的重新同步

        assertEquals(Set.of("R3"), registry.disabledRuleIds(),
                "重新同步绝不能把 enabled 重置成默认值");
    }

    @Test
    @DisplayName("停用集合能直接喂给 RuleEngine，且那条规则确实不再求值")
    void disabledIdsFeedStraightIntoRuleEngine() {
        registry.syncKnownRules(allRules());
        registry.setEnabled("R1", false);

        RuleEngine engine = new RuleEngine(allRules(), registry.disabledRuleIds());
        // 引擎自己也会把「被停用」记进效果里，这里验证接线是通的。
        assertEquals(4, engine.size());
        assertEquals(Set.of("R1"), registry.disabledRuleIds());
    }

    // ------------------------------------------------------------- 命中统计

    @Test
    @DisplayName("recordHits 累加命中次数")
    void recordHitsAccumulates() throws SQLException {
        registry.syncKnownRules(allRules());

        registry.recordHits(List.of("R1", "R2"));
        registry.recordHits(List.of("R1"));

        assertEquals(2L, hitCount("R1"));
        assertEquals(1L, hitCount("R2"));
        assertEquals(0L, hitCount("R3"), "没命中的规则必须停在 0——这正是"
                + "「长期为 0 说明它没用，应该删掉」那个判断的依据");
    }

    @Test
    @DisplayName("空集合是 no-op")
    void recordHitsWithEmptyCollectionIsANoOp() throws SQLException {
        registry.syncKnownRules(allRules());
        registry.recordHits(List.of());
        assertEquals(0L, hitCount("R1"));
    }

    /**
     * ★ {@code recordHits} 与 {@code setEnabled} 面对同一个「id 不认识」的情形，
     * 但处理方式相反，这是刻意的：
     * 命中统计是<b>观测数据</b>，不该让一次统计失败把已经算出来的安全结论
     * （几人审批、放权上限）一起带崩。
     */
    @Test
    @DisplayName("★ recordHits 对未知 id 静默忽略，不抛异常")
    void recordHitsIgnoresUnknownIdsSilently() throws SQLException {
        registry.syncKnownRules(allRules());

        registry.recordHits(List.of("R1", "不存在的规则"));

        assertEquals(1L, hitCount("R1"), "已知 id 照常累加");
        assertFalse(rowExists("不存在的规则"), "未知 id 不得被写进表");
    }

    /**
     * ★ {@code updated_at} 的语义是「开关最后一次被人改动的时间」。
     *
     * <p>如果每次命中都刷新它，「这条规则是三个月前被停用的」这个信息就被冲掉了 ——
     * 而那正是复盘时最需要知道的事。
     */
    @Test
    @DisplayName("★ recordHits 不刷新 updated_at")
    void recordHitsDoesNotTouchUpdatedAt() throws SQLException, InterruptedException {
        registry.syncKnownRules(allRules());
        registry.setEnabled("R1", false);
        Timestamp afterToggle = updatedAt("R1");

        Thread.sleep(20L);   // 确保时间戳有可分辨的间隔
        registry.recordHits(List.of("R1", "R1"));

        // 断言「没有前进」而不是「完全相等」：后者依赖 H2 对 TIMESTAMP 纳秒的
        // 往返精度，可能因为精度截断而假失败。真正的不变量是它没被刷新。
        assertFalse(updatedAt("R1").after(afterToggle),
                "命中计数不得改动「开关最后被人改动的时间」");
    }

    @Test
    @DisplayName("setEnabled 会刷新 updated_at")
    void setEnabledDoesTouchUpdatedAt() throws SQLException, InterruptedException {
        registry.syncKnownRules(allRules());
        Timestamp afterSync = updatedAt("R1");

        Thread.sleep(20L);
        registry.setEnabled("R1", false);

        assertTrue(updatedAt("R1").after(afterSync),
                "改开关是一次人为改动，必须留下时间");
    }

    // ------------------------------------------------------------- 入参守卫

    @Test
    @DisplayName("入参守卫：null dataSource / 空表名 / null 集合一律拒绝")
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcRuleRegistry(null));
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcRuleRegistry(dataSource, "  "));
        assertThrows(NullPointerException.class, () -> registry.recordHits(null));
        assertThrows(NullPointerException.class, () -> registry.syncKnownRules(null));
        assertThrows(NullPointerException.class, () -> registry.setEnabled(null, false));
    }

    @Test
    @DisplayName("表名可注入，测试因此能指向临时表")
    void tableNameIsInjectable() {
        JdbcRuleRegistry custom = new JdbcRuleRegistry(dataSource, "onto_rule_test");
        custom.createSchemaIfMissing();
        assertTrue(custom.disabledRuleIds().isEmpty());
    }
}
