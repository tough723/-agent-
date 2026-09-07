package com.oncall.agent.plan;

import com.oncall.domain.plan.Plan;
import com.oncall.domain.plan.PlanStep;
import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolDeniedException;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.toolgateway.ToolPolicyEngine;

import java.util.Objects;

/**
 * 计划静态校验：Planner 输出之后、执行之前。
 *
 * <p>这是<b>确定性防线</b>——不依赖模型是否听话。
 * 三条检查对应 {@code 修复方案.md} F2.3 的 ①②③。
 *
 * <h2>★ 第 ③ 条：我改了设计文档的写法，因为原文有个真实漏洞</h2>
 * <p>F2.3 原文是：
 * <pre>{@code if (p.risk() == RiskLevel.HIGH && s.step() < policy.minInvestigationSteps())}</pre>
 * 它只看<b>步骤位置</b>，不看前面那几步到底做了什么。于是
 * {@code minInvestigationSteps = 3} 时，计划 {@code [写, 写, 写]} 的
 * 第 3 步会通过检查（{@code 3 < 3} 为假），<b>而它前面一步信息收集都没有</b>。
 *
 * <p>本实现改成<b>数前面真正做了几步只读探查</b>：
 * 统计该步之前 {@code risk == READ_ONLY} 的步骤数，不足则拒绝。
 * 这样 {@code [写, 写, 写]} 在第 1 步就被拒；
 * 而 {@code [读, 读, 读, 写]} 通过——这才是这条规则想表达的意思。
 *
 * <p>顺带一提，「数只读步骤」也比「数非高危步骤」严格：
 * 一个 LOW 风险的写操作并不是信息收集，不该被算进探查预算。
 *
 * <h2>为什么整份计划一起拒绝，而不是跳过坏步骤</h2>
 * <p>计划是有<b>因果链</b>的：第 4 步的写操作可能依赖第 2 步读到的数据。
 * 悄悄删掉第 2 步，第 4 步就会拿着不存在的前提去执行——
 * 那比整份拒绝危险得多。
 */
public final class PlanValidator {

    private final ToolPolicyEngine policyEngine;
    private final int minInvestigationSteps;

    /**
     * @param minInvestigationSteps 高危动作之前必须已完成的最少<b>只读探查</b>步数。
     *        必须 &ge; 1：取 0 等于取消这条防线，那不该由调用方悄悄做到。
     */
    public PlanValidator(ToolPolicyEngine policyEngine, int minInvestigationSteps) {
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
        if (minInvestigationSteps < 1) {
            throw new IllegalArgumentException("minInvestigationSteps 必须 >= 1，实际 "
                    + minInvestigationSteps + "——取 0 等于取消「写操作前必须先探查」这条防线，"
                    + "不该由调用方悄悄做到");
        }
        this.minInvestigationSteps = minInvestigationSteps;
    }

    /**
     * 校验整份计划。
     *
     * @throws PlanRejectedException 任何一步不合规——整份计划拒绝，不做部分放行
     */
    public void validate(Plan plan) {
        Objects.requireNonNull(plan, "plan");
        int investigationSoFar = 0;

        for (PlanStep step : plan.steps()) {
            // ① 工具必须在白名单内。resolve() 对未注册工具抛 ToolDeniedException，
            //    这里转成 PlanRejectedException，让调用方只处理一种异常类型。
            ToolPolicy policy;
            try {
                policy = policyEngine.resolve(step.action());
            } catch (ToolDeniedException e) {
                throw new PlanRejectedException(step.seq(),
                        PlanRejectedException.Reason.TOOL_NOT_ALLOWED,
                        "step " + step.seq() + " 的动作不在工具白名单内，整份计划拒绝", e);
            }

            if (policy.risk() == RiskLevel.HIGH) {
                // ② 高危步骤必须能追溯到可信来源（告警规则 / Runbook）。
                if (!step.hasTrustedBasis()) {
                    throw new PlanRejectedException(step.seq(),
                            PlanRejectedException.Reason.NO_TRUSTED_BASIS,
                            "step " + step.seq() + " 是高危动作但没有可信依据"
                                    + "（依据必须来自告警规则或 Runbook，日志文本与工具输出不算）",
                            null);
                }
                // ③ 高危动作之前必须已有足够的只读探查——数实质，不数位置。
                if (investigationSoFar < minInvestigationSteps) {
                    throw new PlanRejectedException(step.seq(),
                            PlanRejectedException.Reason.INSUFFICIENT_INVESTIGATION,
                            "step " + step.seq() + " 是高危动作，但它之前只完成了 "
                                    + investigationSoFar + " 步只读探查，少于要求的 "
                                    + minInvestigationSteps + " 步", null);
                }
            }

            // 只读步骤才计入探查预算。LOW 风险的写操作不是信息收集。
            if (policy.risk() == RiskLevel.READ_ONLY) {
                investigationSoFar++;
            }
        }
    }
}
