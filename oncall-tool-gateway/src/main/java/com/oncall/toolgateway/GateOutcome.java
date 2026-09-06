package com.oncall.toolgateway;

/**
 * 关卡结论 —— 对应 {@code tool_audit_log.gate_outcome}。
 *
 * <p><b>取值闭集与 DDL 注释逐字一致</b>（{@code db/migration/V2__agent_execution.sql:85}：
 * 「PASSED / DENIED / CLAMPED / TIMED_OUT；让『哪道关卡拦下了它』可统计」）。
 * 刻意没有 {@code FAILED}、也没有 {@code APPROVED}：
 *
 * <ul>
 *   <li><b>没有 {@code FAILED}</b>：这一列问的是「哪道关卡拦下了它」，
 *       而工具自己抛异常并不是关卡拦截——关卡放行了它，是它自己失败。
 *       把两者混进同一列，「被安全机制拦下的比例」这个指标就再也算不出来了。
 *       执行失败记在 {@code result_masked} 里。</li>
 *   <li><b>没有 {@code APPROVED}</b>：审批有它自己的表 {@code approval_record}
 *       （含 requester / approver / decision / requested_at / decided_at），
 *       那是责任归属的凭据。在审计流水里再记一条 {@code APPROVED}
 *       等于同一个事实存两份，而两份迟早不一致。
 *       所以：批准 → 不单独成行（最终的 {@link #PASSED} 已经覆盖）；
 *       拒绝 → {@link #DENIED}；超时 → {@link #TIMED_OUT}。</li>
 * </ul>
 *
 * <p><b>一次调用可以产生多行</b>（{@code ToolAuditLog} 是只追加的事件流，不是每次调用一行）：
 * 参数被夹紧后仍然继续执行，就会有 {@link #CLAMPED} 与随后的 {@link #PASSED} 两行。
 * 这是刻意的——夹紧本身是「模型生成了越界参数」的信号，
 * 而它后来执行成功了并不能抹掉这个信号。
 */
public enum GateOutcome {

    /** 全部关卡放行，工具执行完成（成功或失败都算放行）。 */
    PASSED,

    /** 被拦下：策略默认拒绝、kill switch、或审批被拒。原因见 {@code denied_reason}。 */
    DENIED,

    /** 参数被夹紧。<b>不终止执行</b>，随后通常还有一行 {@link #PASSED}。 */
    CLAMPED,

    /** 审批超时。超时是一等公民，不是「没有记录」——它必须触发升级。 */
    TIMED_OUT
}
