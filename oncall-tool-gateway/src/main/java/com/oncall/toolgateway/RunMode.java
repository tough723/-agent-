package com.oncall.toolgateway;

/**
 * 全局运行模式（kill switch）。
 *
 * <p>运维 Agent 的必备保险丝：Agent 行为异常时（例如疯狂生成 scale 计划）一键降级，
 * 不需要发版。原方案完全没有这个开关。
 */
public enum RunMode {
    /** 全自动，含写操作。 */
    FULL,
    /** 只读排查 + 给建议，所有非只读工具一律拒绝。 */
    READ_ONLY,
    /** 完全停用 Agent，退回纯人工 OnCall。 */
    OFF
}
