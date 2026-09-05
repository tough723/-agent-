package com.oncall.ontology;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JDBC 实现的测试，跑在真实 H2 上。
 *
 * <p><b>为什么必须用真数据库</b>：SQL 写错了用假 {@code Connection} 是测不出来的——
 * 假对象只会返回你预设的值，等于把断言写在被测代码里。
 * 这个测试真正跑通的路径是 {@code JdbcOntologyStore.putConcept} / {@code findByMention} /
 * {@code putRelation} 的完整 SQL，包括参数占位符的偏移（那里最容易错）。
 *
 * <p><b>顺带验证了一个设计决定</b>：标签用归一化表而不是 {@code TEXT[]}。
 * {@code TEXT[]} 是 PostgreSQL 专有类型，用了它这个测试就没法存在。
 */
class JdbcOntologyStoreTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    private DataSource dataSource;
    private JdbcOntologyStore store;

    @BeforeEach
    void setUp() {
        JdbcDataSource ds = new JdbcDataSource();
        // 每个测试一个独立库：本体数据会互相干扰，共用库会让测试顺序变成隐藏依赖
        ds.setURL("jdbc:h2:mem:onto" + SEQ.incrementAndGet() + ";DB_CLOSE_DELAY=-1");
        dataSource = ds;
        store = new JdbcOntologyStore(dataSource);
        store.createSchemaIfMissing();
    }

    private static OntoConcept concept(String id, String pref, List<String> alt, List<String> hidden,
                                       String parent, ConceptKind kind, Criticality crit) {
        return new OntoConcept(id, pref, alt, hidden, parent, kind, crit, "desc-" + id);
    }

    @Test
    @DisplayName("建表幂等：重复调用不报错")
    void createSchemaIsIdempotent() {
        store.createSchemaIfMissing();
        store.createSchemaIfMissing();
        assertEquals(0, store.conceptCount());
    }

    @Test
    @DisplayName("概念往返：三类标签都能读回来")
    void conceptRoundTrip() {
        store.putConcept(concept("service:pay", "支付网关",
                List.of("payment-api", "支付"), List.of("支付挂关"),
                null, ConceptKind.SERVICE, Criticality.CRITICAL));

        OntoConcept loaded = store.concept("service:pay").orElseThrow();
        assertEquals("支付网关", loaded.prefLabel());
        assertEquals(List.of("payment-api", "支付"), loaded.altLabels());
        assertEquals(List.of("支付挂关"), loaded.hiddenLabels());
        assertEquals(ConceptKind.SERVICE, loaded.kind());
        assertEquals(Criticality.CRITICAL, loaded.criticality());
        assertEquals("desc-service:pay", loaded.description());
        assertTrue(loaded.isRoot());
    }

    @Test
    @DisplayName("更新概念会替换标签：删掉的旧别名必须不再命中")
    void updateReplacesLabelsNotAppends() {
        store.putConcept(concept("service:pay", "支付网关", List.of("payment-api", "旧别名"),
                List.of(), null, ConceptKind.SERVICE, Criticality.NORMAL));
        store.putConcept(concept("service:pay", "支付网关", List.of("payment-api"),
                List.of(), null, ConceptKind.SERVICE, Criticality.CRITICAL));

        assertEquals(1, store.conceptCount(), "更新不应新增行");
        assertEquals(Criticality.CRITICAL, store.concept("service:pay").orElseThrow().criticality());
        assertEquals(List.of("payment-api"), store.concept("service:pay").orElseThrow().altLabels());
        assertTrue(store.findByMention("旧别名").isEmpty(), "旧别名必须不再命中，否则实体链接会指向已删除的语义");
    }

    @Test
    @DisplayName("标签查询大小写不敏感，且走 label_norm 列")
    void findByMentionIsCaseInsensitive() {
        store.putConcept(concept("service:pay", "支付网关", List.of("payment-api"),
                List.of(), null, ConceptKind.SERVICE, Criticality.NORMAL));
        assertEquals(1, store.findByMention("PAYMENT-API").size());
        assertEquals(1, store.findByMention("Payment-Api").size());
        assertEquals(1, store.findByMention("  payment-api  ").size(), "首尾空白应被忽略");
        assertEquals(0, store.findByMention("payment").size(), "不做前缀匹配");
    }

    @Test
    @DisplayName("多义词返回全部命中")
    void findByMentionReturnsAllHits() {
        store.putConcept(concept("domain:pay", "支付域", List.of("支付"), List.of(),
                null, ConceptKind.DOMAIN, Criticality.NORMAL));
        store.putConcept(concept("service:pay", "支付网关", List.of("支付"), List.of(),
                null, ConceptKind.SERVICE, Criticality.CRITICAL));
        List<OntoConcept> hits = store.findByMention("支付");
        assertEquals(2, hits.size());
        assertEquals("domain:pay", hits.get(0).id());
        assertEquals("service:pay", hits.get(1).id());
    }

    @Test
    @DisplayName("父子关系与 is-a 上溯在 JDBC 实现上同样成立")
    void hierarchyWorks() {
        store.putConcept(concept("alert:resource", "资源类告警", List.of(), List.of(),
                null, ConceptKind.ALERT, Criticality.NORMAL));
        store.putConcept(concept("alert:disk", "磁盘告警", List.of(), List.of(),
                "alert:resource", ConceptKind.ALERT, Criticality.NORMAL));

        assertEquals(List.of("alert:disk"),
                store.children("alert:resource").stream().map(OntoConcept::id).toList());
        Ontology ontology = new Ontology(store);
        assertTrue(ontology.isA("alert:disk", "alert:resource"));
        assertFalse(ontology.isA("alert:resource", "alert:disk"));
    }

    @Test
    @DisplayName("children(null) 返回空而不是全部顶层概念")
    void childrenOfNullIsEmpty() {
        store.putConcept(concept("a:1", "A1", List.of(), List.of(), null, ConceptKind.SERVICE,
                Criticality.NORMAL));
        assertTrue(store.children(null).isEmpty());
    }

    @Test
    @DisplayName("allConcepts 按 id 排序：遍历结果会进提示词，顺序必须稳定")
    void allConceptsIsSorted() {
        store.putConcept(concept("svc:z", "Z", List.of(), List.of(), null, ConceptKind.SERVICE,
                Criticality.NORMAL));
        store.putConcept(concept("svc:a", "A", List.of(), List.of(), null, ConceptKind.SERVICE,
                Criticality.NORMAL));
        store.putConcept(concept("svc:m", "M", List.of(), List.of(), null, ConceptKind.SERVICE,
                Criticality.NORMAL));
        assertEquals(List.of("svc:a", "svc:m", "svc:z"),
                store.allConcepts().stream().map(OntoConcept::id).toList());
    }

    @Test
    @DisplayName("关系往返与双向查询")
    void relationRoundTrip() {
        store.putRelation(new OntoRelation("svc:pay", OntoRelation.DEPENDS_ON, "svc:db", "CMDB"));
        store.putRelation(new OntoRelation("svc:order", OntoRelation.DEPENDS_ON, "svc:pay", "CMDB"));

        assertEquals(List.of("svc:db"), store.objects("svc:pay", OntoRelation.DEPENDS_ON));
        assertEquals(List.of("svc:order"), store.subjects("svc:pay", OntoRelation.DEPENDS_ON));
        assertTrue(store.objects("svc:db", OntoRelation.DEPENDS_ON).isEmpty());
    }

    @Test
    @DisplayName("关系 upsert 幂等：写两次只有一行，且 source 被覆盖")
    void relationUpsertIsIdempotent() {
        store.putRelation(new OntoRelation("svc:pay", OntoRelation.DEPENDS_ON, "svc:db", "CMDB"));
        store.putRelation(new OntoRelation("svc:pay", OntoRelation.DEPENDS_ON, "svc:db", "MANUAL"));
        assertEquals(1, store.objects("svc:pay", OntoRelation.DEPENDS_ON).size());
        assertEquals(1, store.relationCountBySource().getOrDefault("MANUAL", 0).intValue());
        assertEquals(0, store.relationCountBySource().getOrDefault("CMDB", 0).intValue());
    }

    @Test
    @DisplayName("按来源统计关系：CMDB 关系为 0 是本体会退化成受控词表的信号")
    void relationCountBySource() {
        store.putRelation(new OntoRelation("a", OntoRelation.DEPENDS_ON, "b", "CMDB"));
        store.putRelation(new OntoRelation("a", OntoRelation.DEPENDS_ON, "c", "CMDB"));
        store.putRelation(new OntoRelation("a", OntoRelation.OWNED_BY, "team:x", "MANUAL"));
        assertEquals(2, store.relationCountBySource().get("CMDB").intValue());
        assertEquals(1, store.relationCountBySource().get("MANUAL").intValue());
    }

    @Test
    @DisplayName("有界遍历在 JDBC 实现上同样成立")
    void boundedTraversalWorks() {
        store.putRelation(new OntoRelation("s1", OntoRelation.DEPENDS_ON, "s2", "CMDB"));
        store.putRelation(new OntoRelation("s2", OntoRelation.DEPENDS_ON, "s3", "CMDB"));
        store.putRelation(new OntoRelation("s3", OntoRelation.DEPENDS_ON, "s4", "CMDB"));
        store.putRelation(new OntoRelation("s4", OntoRelation.DEPENDS_ON, "s5", "CMDB"));
        assertEquals(java.util.Set.of("s2", "s3", "s4"), new Ontology(store).impactScope("s1"));
    }

    @Test
    @DisplayName("null dataSource 立即失败，而不是等到第一次查询")
    void nullDataSourceRejected() {
        assertThrows(IllegalArgumentException.class, () -> new JdbcOntologyStore(null));
    }

    @Test
    @DisplayName("查不到的概念返回 empty")
    void missingConceptIsEmpty() {
        assertTrue(store.concept("nope").isEmpty());
        assertEquals(Criticality.NORMAL, new Ontology(store).criticalityOf("nope"));
    }
}
