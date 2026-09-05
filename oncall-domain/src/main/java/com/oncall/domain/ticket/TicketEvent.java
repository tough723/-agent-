package com.oncall.domain.ticket;

/** 驱动状态机转移的事件。 */
public enum TicketEvent {
    ACK,
    START_AI,
    ROOT_CAUSE_FOUND,
    CANNOT_DIAGNOSE,
    PROPOSE_ACTION,
    APPROVED,
    REJECTED,
    /** 审批超时——原方案全文未出现"超时"，这是补上的关键出口。 */
    APPROVAL_TIMEOUT,
    EXEC_SUCCESS,
    EXEC_FAILURE,
    EXEC_TIMEOUT,
    COMPENSATED,
    HUMAN_TAKEOVER,
    START_REVIEW,
    KB_INDEXED
}
