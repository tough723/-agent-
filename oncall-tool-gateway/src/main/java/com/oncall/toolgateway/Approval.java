package com.oncall.toolgateway;

/**
 * 审批结果。
 *
 * @param approved    是否批准
 * @param approver    审批人；超时或拒绝时为 null
 * @param reason      拒绝理由或超时说明，会回灌给模型让它改走别的路径
 * @param expired     是否因超时结束——超时必须触发升级，不能让工单永久卡在 PENDING_APPROVAL
 */
public record Approval(boolean approved, String approver, String reason, boolean expired) {

    public static Approval approved(String approver) {
        return new Approval(true, approver, null, false);
    }

    public static Approval rejected(String approver, String reason) {
        return new Approval(false, approver, reason, false);
    }

    /** 审批超时：不是卡死，是升级信号。 */
    public static Approval expired() {
        return new Approval(false, null, "approval timed out", true);
    }
}
