package com.oncall.toolgateway;

import com.oncall.domain.tool.ToolPolicy;

import java.time.Duration;

/**
 * 审批闸门。
 *
 * <p>【修正】原方案 §4.3 承诺"编排器在执行前插入人工审批节点"，
 * 但 §3.3 的 {@code handle()} 循环里 {@code executeStep(step)} 是无条件直接执行的——
 * 按那份代码，{@code execute_action("scale")} 会绕过审批直接落地。这里把闸门做实。
 *
 * <p>关键约束：<b>必须带超时</b>。原方案全文检索"超时"命中 0 次，
 * 而 {@code PENDING_APPROVAL} 一旦审批人不在线就会永久卡死，告警还在烧。
 */
public interface ApprovalGate {

    /**
     * 阻塞等待审批结果，超时返回 {@link Approval#timedOut()}。
     *
     * <p>实现方职责：
     * <ol>
     *   <li>写 {@code approval_record}（含 {@code expires_at}）</li>
     *   <li>触发工单状态机 {@code PROPOSE_ACTION} → {@code PENDING_APPROVAL}</li>
     *   <li>推送企微/钉钉审批卡片</li>
     *   <li>超时时：标记 EXPIRED + 升级通知值班长 + 触发状态机 {@code APPROVAL_TIMEOUT}</li>
     * </ol>
     *
     * @param idempotencyKey 幂等键，与工具执行共用，保证审计可追溯
     * @param policy         工具策略（含风险等级、是否双人复核、超时时长）
     * @param args           已夹紧的参数——审批人看到的必须是真正会执行的参数
     * @return 审批结果，永不为 null
     */
    Approval await(String idempotencyKey, ToolPolicy policy, String args);

    /** 便捷方法：使用策略里配置的超时时长。 */
    default Duration timeoutOf(ToolPolicy policy) {
        return policy.approvalTimeout();
    }
}
