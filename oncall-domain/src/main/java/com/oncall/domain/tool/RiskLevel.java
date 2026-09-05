package com.oncall.domain.tool;

/** 工具风险等级。决定是否需要审批、是否被 kill switch 拦截。 */
public enum RiskLevel {
    /** 只读：Agent 可直接调用。 */
    READ_ONLY,
    /** 低危：可调用，需二次确认。 */
    LOW,
    /** 高危：必须人工审批才能落地。 */
    HIGH
}
