package com.oncall.ontology;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本体门面的行为测试。
 *
 * <p>重点不在「能不能查到」，而在三件容易错的事：
 * <ol>
 *   <li><b>遍历有界</b>——脏数据（环）和调用方传大数都不能让它跑飞；</li>
 *   <li><b>多义不猜</b>——歧义必须显式暴露成候选，不能默默挑一个；</li>
 *   <li><b>结果确定</b>——同样输入必须给出同样输出，否则提示词会无理由抖动。</li>
 * </ol>
 */
class OntologyTest {

    private final Ontology ontology = new Ontology(OntologyFixture.store());

    // ------------------------------------------------------------ 实体链接

    @Test
    @DisplayName("首选标签命中 → 唯一解析")
    void linksByPrefLabel() {
        EntityLink link = ontology.link("支付网关");
        assertTrue(link.isResolved());
        assertEquals("service:payment-gateway", link.resolved().id());
    }

    @Test
    @DisplayName("别名命中 → 唯一解析（受控词表被本体吸收，不是两套并存）")
    void linksByAltLabel() {
        EntityLink link = ontology.link("支付服务");
        assertTrue(link.isResolved());
        assertEquals("service:payment-batch", link.resolved().id());
    }

    @Test
    @DisplayName("拼错的词也能命中 → 用户会打错，检索不能因此失败")
    void linksByHiddenLabel() {
        EntityLink link = ontology.link("磁盘爆了");
        assertTrue(link.isResolved());
        assertEquals("alert:disk", link.resolved().id());
    }

    @Test
    @DisplayName("大小写不敏感 → 用户不会按规范大小写输入")
    void matchingIsCaseInsensitive() {
        EntityLink link = ontology.link("PAYMENT-API");
        assertTrue(link.isResolved());
        assertEquals("service:payment-gateway", link.resolved().id());
    }

    @Test
    @DisplayName("多义词返回候选而不是猜 —— 别名表给不了这个能力")
    void ambiguityIsExposedNotGuessed() {
        EntityLink link = ontology.link("支付");
        assertTrue(link.needsClarification(), "「支付」同时是支付域和支付网关的别名，必须澄清");
        assertEquals(2, link.candidates().size());
        String prompt = link.clarificationPrompt("支付");
        assertTrue(prompt.contains("支付域"), prompt);
        assertTrue(prompt.contains("支付网关"), prompt);
        assertTrue(prompt.contains("还是"), prompt);
    }

    @Test
    @DisplayName("别名与首选标签重名时，首选标签精确命中优先")
    void exactPrefLabelWinsTieBreak() {
        EntityLink link = ontology.link("订单服务");
        assertTrue(link.isResolved(), "有一个概念的 prefLabel 就叫「订单服务」，不应判为多义");
        assertEquals("service:order", link.resolved().id());
    }

    @Test
    @DisplayName("查不到 → UNRESOLVED，由调用方降级为纯文本检索")
    void unknownMentionIsUnresolved() {
        EntityLink link = ontology.link("不存在的词");
        assertEquals(EntityLink.Status.UNRESOLVED, link.status());
        assertFalse(link.isResolved());
        assertFalse(link.needsClarification());
    }

    @Test
    @DisplayName("空输入不抛异常")
    void blankMentionIsSafe() {
        assertEquals(EntityLink.Status.UNRESOLVED, ontology.link(null).status());
        assertEquals(EntityLink.Status.UNRESOLVED, ontology.link("   ").status());
    }

    // ------------------------------------------------------------ is-a 遍历

    @Test
    @DisplayName("is-a 沿 is-a 上溯成立")
    void isaFollowsHierarchy() {
        assertTrue(ontology.isA("alert:disk", "alert:resource"));
        assertTrue(ontology.isA("alert:cpu", "alert:resource"));
        assertTrue(ontology.isA("alert:resource", "alert:resource"), "自身也算");
    }

    @Test
    @DisplayName("不同分支不成立 —— 这是 R3 判「是不是资源类告警」的依据")
    void isaRejectsOtherBranch() {
        assertFalse(ontology.isA("alert:biz", "alert:resource"));
        assertFalse(ontology.isA("alert:resource", "alert:disk"), "方向反了");
    }

    @Test
    @DisplayName("未知概念不成立，且不抛异常")
    void isaWithUnknownIds() {
        assertFalse(ontology.isA("nope", "alert:resource"));
        assertFalse(ontology.isA("alert:disk", null));
        assertFalse(ontology.isA(null, "alert:resource"));
    }

    @Test
    @DisplayName("is-a 成环时不死循环 —— 脏数据不能挂死请求线程")
    void isaTerminatesOnCycle() {
        Ontology cyclic = new Ontology(OntologyFixture.cyclicStore());
        assertFalse(cyclic.isA("svc:a", "svc:not-exist"));
        // 环里互相指向，能查到但不该无限展开
        assertEquals(2, cyclic.ancestors("svc:a").size());
    }

    @Test
    @DisplayName("ancestors 从近到远")
    void ancestorsOrdered() {
        assertEquals(List.of("alert:disk", "alert:resource"), ontology.ancestors("alert:disk"));
    }

    @Test
    @DisplayName("descendants 展开整个业务域 —— CQ1 的解法，别名表只能一对一")
    void descendantsExpandDomain() {
        List<String> all = ontology.descendants("domain:payment");
        assertEquals(List.of("domain:payment", "service:payment-batch", "service:payment-gateway"), all);
    }

    @Test
    @DisplayName("结果确定：连续两次遍历输出完全一致（提示词不能无理由抖动）")
    void traversalIsDeterministic() {
        for (int i = 0; i < 20; i++) {
            assertEquals(ontology.descendants("domain:payment"),
                    new Ontology(OntologyFixture.store()).descendants("domain:payment"));
        }
    }

    // ------------------------------------------------------------ 有界关系遍历

    @Test
    @DisplayName("1 跳只返回直接下游")
    void oneHopOnly() {
        List<Set<String>> hops = ontology.traverse("service:payment-gateway", OntoRelation.DEPENDS_ON, 1);
        assertEquals(1, hops.size());
        assertEquals(Set.of("service:payment-batch"), hops.get(0));
    }

    @Test
    @DisplayName("传超大跳数会被夹到 MAX_HOPS —— 调用方不能绕过上限")
    void hopLimitIsClamped() {
        List<Set<String>> hops = ontology.traverse("service:payment-gateway", OntoRelation.DEPENDS_ON, 99);
        assertEquals(Ontology.MAX_HOPS, hops.size());
    }

    @Test
    @DisplayName("非法跳数被夹到 1 而不是抛异常")
    void nonPositiveHopsClampedToOne() {
        assertEquals(1, ontology.traverse("service:payment-gateway", OntoRelation.DEPENDS_ON, 0).size());
        assertEquals(1, ontology.traverse("service:payment-gateway", OntoRelation.DEPENDS_ON, -5).size());
    }

    @Test
    @DisplayName("影响面是有界的：4 跳之外的服务不出现")
    void impactScopeIsBounded() {
        Set<String> scope = ontology.impactScope("service:payment-gateway");
        assertEquals(Set.of("service:payment-batch", "service:db-primary", "service:storage"), scope);
        assertFalse(scope.contains("service:beyond-horizon"),
                "无界影响面在运维上没有意义：「影响整个公司」不是可执行的答案");
    }

    @Test
    @DisplayName("自身不算进影响面")
    void impactScopeExcludesSelf() {
        assertFalse(ontology.impactScope("service:payment-gateway").contains("service:payment-gateway"));
    }

    @Test
    @DisplayName("反向查询：谁依赖我")
    void reverseLookup() {
        assertEquals(List.of("service:order"),
                ontology.store().subjects("service:payment-gateway", OntoRelation.DEPENDS_ON));
    }

    @Test
    @DisplayName("没有下游时返回空集而不是 null")
    void leafHasEmptyScope() {
        Set<String> scope = ontology.impactScope("service:cache");
        assertNotNull(scope);
        assertTrue(scope.isEmpty(), "缓存服务没有任何出边，影响面必须为空");
    }

    // ------------------------------------------------------------ 属性查询

    @Test
    @DisplayName("criticality 是属性：同一层级下不同服务可以不同关键度")
    void criticalityIsAPropertyNotASubclass() {
        assertEquals(Criticality.CRITICAL, ontology.criticalityOf("service:payment-gateway"));
        assertEquals(Criticality.NORMAL, ontology.criticalityOf("service:payment-batch"));
        assertEquals(Criticality.HIGH, ontology.criticalityOf("service:order"));
    }

    @Test
    @DisplayName("查不到关键度时按 NORMAL —— 缺数据不放大约束，但也不放行写操作")
    void unknownConceptDefaultsToNormal() {
        assertEquals(Criticality.NORMAL, ontology.criticalityOf("service:does-not-exist"));
    }

    @Test
    @DisplayName("Runbook 过期判定用传入的时钟，不读系统时间")
    void stalenessUsesInjectedTime() {
        long now = 1_800_000_000_000L;
        long day = 24L * 60 * 60 * 1000;
        assertTrue(Ontology.isRunbookStale(now - 181 * day, now, 180 * day));
        assertFalse(Ontology.isRunbookStale(now - 179 * day, now, 180 * day));
        assertFalse(Ontology.isRunbookStale(now, now, 180 * day));
    }
}
