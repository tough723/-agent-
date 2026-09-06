package com.oncall.toolgateway;

import com.oncall.domain.tool.ToolPolicy;

/**
 * 审批闸门：高风险操作的放行入口。
 *
 * <p>原方案 6.4 的契约：先写 {@code approval_record}，再阻塞等待外部回调。
 * 审批人不能是 Agent 自己（数据库约束 {@code chk_approval_not_self}）。
 *
 * <p><b>本接口此前在生产代码里没有任何实现</b>——
 * 契约、javadoc、DDL 都写好了，但没有东西真的会拦住一次高危操作，
 * {@code GuardedToolCallback} 里注入的 {@code approvalGate} 只能由测试提供 lambda。
 * 现在 {@link PollingApprovalGate} 是第一个实现。
 *
 * <h2>为什么方法签名里有 {@link ToolAuditContext}</h2>
 * <p>原先是 {@code await(idempotencyKey, policy, args)}。
 * 但 {@code approval_record} 的 {@code trace_id} 是 <b>NOT NULL</b>，
 * 而这个签名里<b>没有任何一个参数能提供它</b>——
 * 也就是说，任何按原签名写的实现都只能往 trace_id 里塞假值。
 * <b>接口的参数列表既可能少给也可能多给</b>：这里是少给了一个必填列的来源。
 * 补上 {@code context} 之后，实现才能诚实地落库。
 *
 * <h2>必须超时</h2>
 * <p>{@code PENDING_APPROVAL} 一旦审批人不在线就会永久卡死，告警还在烧。
 * 原方案全文检索「超时」命中 0 次。实现必须用策略里的
 * {@link ToolPolicy#approvalTimeout()}，并在超时后把记录写成
 * {@link ApprovalDecision#TIMED_OUT} 而不是留一个永远 {@code PENDING} 的行。
 */
public interface ApprovalGate {

    /**
     * 等待审批。
     *
     * <p>实现必须：
     * <ol>
     *   <li><b>先写 {@code approval_record} 再等待</b>——否则进程在等待期间崩溃，
     *       这次审批在数据库里就不存在，而操作可能已经被批了；</li>
     *   <li>快照用<b>夹紧且脱敏后</b>的参数（DDL 注释：「否则这次审批无效」）；</li>
     *   <li>阻塞至有结论或超时；超时返回 {@link Approval#expired()}
     *       并把记录写成 {@code TIMED_OUT}；</li>
     *   <li>审批人不得等于申请人（数据库已有 {@code chk_approval_not_self} 约束，
     *       但实现应在内存里就先拒绝——撞数据库约束时
     *       「谁试图自批」这个安全信号就丢了）。</li>
     * </ol>
     *
     * <p><b>不要写 {@code expires_at}</b>：{@code approval_record} <b>没有这个列</b>
     * （本接口的 javadoc 曾要求写它，那是错的）。到期时刻可以由
     * {@code requested_at + ToolPolicy.approvalTimeout()} 算出来，
     * 而策略里的超时值是可能被改的——把算出来的值存成列，
     * 就会在策略改动后与事实不一致。
     *
     * @param idempotencyKey 用于把审批记录与那次工具调用对应起来
     * @param policy         工具策略，提供 {@code approvalTimeout}
     * @param args           调用参数原文（由实现负责夹紧 + 脱敏后再落库）
     * @param context        trace / run / step / operator；
     *                       {@code trace_id} 是 NOT NULL，所以这个参数不可为 null
     */
    Approval await(String idempotencyKey, ToolPolicy policy, String args,
                   ToolAuditContext context);
}
