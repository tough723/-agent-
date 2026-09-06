package com.oncall.toolgateway;

/**
 * 审批结论 —— 对应 {@code approval_record.decision}。
 *
 * <p><b>为什么多一个 {@code PENDING}</b>：V2 的列注释只写了
 * 「GRANTED / REJECTED / TIMED_OUT」，那是按「审批已经结束」想的。
 * 但 {@code ApprovalGate} 的契约要求<b>在等待之前</b>就把记录写下来
 * （否则无法回答「现在有哪些操作正卡着等人批」，
 * 也无法在超时后把它们找出来升级）。
 *
 * <p>所以 {@code PENDING} 是必需的，V8 迁移把列注释补上了。
 * 少一个 PENDING 的代价不是「少一种状态」，而是
 * <b>卡住的审批在数据库里不可见</b>——运维只能靠告警还在烧来倒推。
 */
public enum ApprovalDecision {

    /** 已提交，等待审批人处理。{@code decided_at} 与 {@code approver} 必须为空。 */
    PENDING,

    /** 已批准。必须有审批人。 */
    GRANTED,

    /** 已拒绝。必须有审批人。 */
    REJECTED,

    /**
     * 超时。
     *
     * <p><b>超时是一等公民，不是「没有记录」</b>：它必须触发升级通知值班长，
     * 而 {@code approver} 必须为空——没有人做过这个决定，
     * 填一个人名进审批人字段等于伪造责任归属。
     */
    TIMED_OUT;

    /** 是否已经是终态（不会再变）。 */
    public boolean isFinal() {
        return this != PENDING;
    }

    /** 是否放行了这次操作。 */
    public boolean isApproved() {
        return this == GRANTED;
    }
}
