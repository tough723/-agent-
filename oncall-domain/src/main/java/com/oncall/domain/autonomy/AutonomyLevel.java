package com.oncall.domain.autonomy;

/**
 * 自动化放权级别（渐进式落地四阶段）。
 *
 * <p>设计依据见《可行性优化与拓展设计》§3。核心思想：<b>不允许从 0 直接跳到"Agent 自动处置"</b>，
 * 必须逐级放权，每级有明确的晋级门槛和降级触发条件。
 *
 * <p>与 {@code RunMode}（kill switch）正交：
 * <ul>
 *   <li>{@code AutonomyLevel} 管「这个系统被允许做到哪一步」——长期配置，变更需走 ADR</li>
 *   <li>{@code RunMode} 管「现在这一刻允许做什么」——运行时开关，可热切换</li>
 * </ul>
 * 两者取交集，任一收紧即生效。
 */
public enum AutonomyLevel {

    /**
     * S0 影子模式：Agent 完整跑，但结果不展示给任何人，只落库。
     * 用于在零风险下积累"AI 结论 vs 人工实际处置"的对比数据。
     */
    SHADOW,

    /** S1 建议模式：结论展示给值班人，决策权 100% 在人。核心指标是采纳率。 */
    SUGGEST,

    /** S2 辅助模式：只读自动，写操作需人点确认。 */
    ASSIST,

    /**
     * S3 限定自动：白名单内的低危操作自动执行（如副本 +1），高危仍需审批。
     * 建议作为长期终态。
     */
    BOUNDED_AUTO;

    /** 该级别下是否允许任何自动写操作。S0-S2 一律不允许。 */
    public boolean allowsAutoExecution() {
        return this == BOUNDED_AUTO;
    }

    /** 该级别下 AI 结论是否对人可见。S0 刻意不可见。 */
    public boolean isVisibleToHuman() {
        return this != SHADOW;
    }
}
