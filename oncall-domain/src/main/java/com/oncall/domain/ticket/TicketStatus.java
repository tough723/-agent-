package com.oncall.domain.ticket;

/**
 * 工单状态。
 *
 * <p>【修正】原方案 DB 注释里的枚举漏了 {@code MANUAL_HANDLING} 与 {@code KNOWLEDGE_INDEXED}，
 * 且状态机图与 DB 注释两边都没有执行失败态——但方案 §3.3 明确写了 "若 Step5 失败 → 自动新增 Step6: rollback"。
 * 这里补齐 {@link #EXEC_FAILED}，并让枚举成为状态机的唯一事实来源。
 */
public enum TicketStatus {
    NEW,
    ACK,
    INVESTIGATING,
    DIAGNOSED,
    PENDING_APPROVAL,
    EXECUTING,
    /** 执行失败（原方案缺失）。补偿完成后转 {@link #MANUAL_HANDLING}。 */
    EXEC_FAILED,
    RESOLVED,
    ESCALATED,
    MANUAL_HANDLING,
    REVIEW,
    KNOWLEDGE_INDEXED;

    /** 终态：不再接受任何转移。 */
    public boolean isTerminal() {
        return this == KNOWLEDGE_INDEXED;
    }

    /** 等待人工的状态：必须有超时出口，否则告警还在烧而工单永久卡死。 */
    public boolean awaitsHuman() {
        return this == PENDING_APPROVAL || this == ESCALATED || this == MANUAL_HANDLING;
    }
}
