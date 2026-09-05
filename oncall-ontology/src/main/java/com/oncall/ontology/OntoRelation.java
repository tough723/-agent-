package com.oncall.ontology;

import java.util.Objects;

/**
 * 一条带类型的关系。
 *
 * <p><b>类型是向量相似度给不了的信息。</b>「A 依赖 B」和「A 部署在 B 上」
 * 在语义空间里几乎重合，但处置方向**相反**——依赖关系出问题查上游，
 * 部署关系出问题查宿主机。带类型的边可以区分，向量检索不能。
 *
 * @param source 数据来源。{@code CMDB} 同步的会被覆盖，{@code MANUAL} 的不会——
 *               这决定了可信度，也决定了同步任务该不该动它
 */
public record OntoRelation(
        String subject,
        String predicate,
        String object,
        String source
) {

    /** 依赖：影响面分析沿这条边。 */
    public static final String DEPENDS_ON = "depends_on";
    /** 部署：定位宿主机沿这条边。 */
    public static final String DEPLOYED_ON = "deployed_on";
    /** 归属：决定该 @ 谁。 */
    public static final String OWNED_BY = "owned_by";
    /** 告警由哪个服务触发。 */
    public static final String TRIGGERS = "triggers";
    /** 某类告警由哪份 Runbook 处置。 */
    public static final String REMEDIATED_BY = "remediated_by";
    /** 某类操作需要的审批级别。 */
    public static final String REQUIRES_APPROVAL = "requires_approval";

    public OntoRelation {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(object, "object");
        source = source == null ? "MANUAL" : source;
    }
}
