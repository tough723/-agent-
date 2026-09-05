package com.oncall.ontology.rule;

import com.oncall.domain.tool.RiskLevel;

/**
 * 规则评估的输入。
 *
 * <p>{@code RiskLevel} 复用 {@code oncall-domain} 的定义——
 * 本体规则要判定的就是这个等级，两处定义必然漂移。
 *
 * <p><b>注意 {@code riskLevel} 为 {@code null} 时按 {@link RiskLevel#HIGH} 处理</b>：
 * MCP 在运行时拉取的工具没有注解，风险等级只能来自本体的 {@code requires_approval} 关系。
 * 查不到时**按最严格处理**，不能按最宽松。
 *
 * @param serviceId          目标服务的概念 id，可为 null
 * @param alertConceptId     告警的概念 id，可为 null。R3 沿它做 is-a 上溯
 * @param operationId        操作的概念 id，可为 null
 * @param riskLevel          工具风险等级，null ⇒ HIGH
 * @param irreversible       是否不可逆操作
 * @param runbookLastUpdated 引用 Runbook 的最后更新时间戳（毫秒）；无引用时为 0
 */
public record RuleContext(
        String serviceId,
        String alertConceptId,
        String operationId,
        RiskLevel riskLevel,
        boolean irreversible,
        long runbookLastUpdated
) {

    /** 没有 Runbook 引用。 */
    public static final long NO_RUNBOOK = 0L;

    public RuleContext {
        riskLevel = riskLevel == null ? RiskLevel.HIGH : riskLevel;
    }

    /** 风险等级达到 HIGH。 */
    public boolean isHighRisk() {
        return riskLevel == RiskLevel.HIGH;
    }

    /** 是否引用了 Runbook。 */
    public boolean hasRunbook() {
        return runbookLastUpdated > 0;
    }
}
