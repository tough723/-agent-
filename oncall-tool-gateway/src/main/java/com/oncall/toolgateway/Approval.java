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

    // 注意：静态工厂不能与 record 组件同名同参。
    // 组件 approved / expired 已隐式生成 boolean approved() / boolean expired() 访问器，
    // 再声明 static Approval expired() 会顶掉访问器，编译报
    // "invalid accessor method in record"，并把调用点的 boolean 用法一起带崩。
    // 所以工厂方法一律用动词命名：granted / rejected / timedOut。

    public static Approval granted(String approver) {
        return new Approval(true, approver, null, false);
    }

    public static Approval rejected(String approver, String reason) {
        return new Approval(false, approver, reason, false);
    }

    /** 审批超时：不是卡死，是升级信号。 */
    public static Approval timedOut() {
        return new Approval(false, null, "approval timed out", true);
    }
}
