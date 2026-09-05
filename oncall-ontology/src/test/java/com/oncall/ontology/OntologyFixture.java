package com.oncall.ontology;

import java.util.List;

/**
 * 测试用的小型本体。
 *
 * <p>规模刻意小（11 个概念、5 条关系），但覆盖了全部会出错的形状：
 * 多义词、拼错的词、别名与首选标签重名、is-a 环、深度超过上限的链、只有反向边的关系。
 */
public final class OntologyFixture {

    private OntologyFixture() {
    }

    public static OntologyStore store() {
        InMemoryOntologyStore s = new InMemoryOntologyStore();

        // ---- 概念层 ----
        concept(s, "domain:payment", "支付域", null, ConceptKind.DOMAIN, Criticality.NORMAL,
                List.of("支付"), List.of());
        concept(s, "service:payment-gateway", "支付网关", "domain:payment",
                ConceptKind.SERVICE, Criticality.CRITICAL, List.of("支付", "payment-api"), List.of());
        concept(s, "service:payment-batch", "支付批处理", "domain:payment",
                ConceptKind.SERVICE, Criticality.NORMAL, List.of("支付服务"), List.of());
        concept(s, "service:order", "订单服务", null,
                ConceptKind.SERVICE, Criticality.HIGH, List.of(), List.of());
        // 别名与另一个概念的首选标签重名：用于验证「首选标签精确命中优先」的消歧
        concept(s, "service:order-legacy", "订单服务(旧)", null,
                ConceptKind.SERVICE, Criticality.NORMAL, List.of("订单服务"), List.of());
        // 叶子节点：没有任何出边，用于验证空影响面返回空集而不是 null
        concept(s, "service:cache", "缓存服务", null,
                ConceptKind.SERVICE, Criticality.NORMAL, List.of(), List.of());

        concept(s, "alert:resource", "资源类告警", null, ConceptKind.ALERT, Criticality.NORMAL,
                List.of(), List.of());
        concept(s, "alert:disk", "磁盘告警", "alert:resource", ConceptKind.ALERT, Criticality.NORMAL,
                List.of(), List.of("磁盘爆了"));
        concept(s, "alert:cpu", "CPU 告警", "alert:resource", ConceptKind.ALERT, Criticality.NORMAL,
                List.of(), List.of());
        concept(s, "alert:biz", "业务告警", null, ConceptKind.ALERT, Criticality.NORMAL,
                List.of(), List.of());

        concept(s, "runbook:payment-timeout", "支付超时处置", null, ConceptKind.REMEDIATION,
                Criticality.NORMAL, List.of(), List.of());

        // ---- 关系层：一条 4 跳的链，用于验证跳数上限 ----
        s.putRelation(new OntoRelation("service:payment-gateway", OntoRelation.DEPENDS_ON,
                "service:payment-batch", "CMDB"));
        s.putRelation(new OntoRelation("service:payment-batch", OntoRelation.DEPENDS_ON,
                "service:db-primary", "CMDB"));
        s.putRelation(new OntoRelation("service:db-primary", OntoRelation.DEPENDS_ON,
                "service:storage", "CMDB"));
        s.putRelation(new OntoRelation("service:storage", OntoRelation.DEPENDS_ON,
                "service:beyond-horizon", "CMDB"));
        // 反向边：谁依赖支付网关
        s.putRelation(new OntoRelation("service:order", OntoRelation.DEPENDS_ON,
                "service:payment-gateway", "CMDB"));
        return s;
    }

    /** 刻意造一个 is-a 环，验证遍历不会因为脏数据而挂死。 */
    public static OntologyStore cyclicStore() {
        InMemoryOntologyStore s = new InMemoryOntologyStore();
        concept(s, "svc:a", "A", "svc:b", ConceptKind.SERVICE, Criticality.NORMAL, List.of(), List.of());
        concept(s, "svc:b", "B", "svc:a", ConceptKind.SERVICE, Criticality.NORMAL, List.of(), List.of());
        return s;
    }

    private static void concept(OntologyStore s, String id, String pref, String parent,
                                ConceptKind kind, Criticality criticality,
                                List<String> alt, List<String> hidden) {
        s.putConcept(new OntoConcept(id, pref, alt, hidden, parent, kind, criticality, null));
    }
}
