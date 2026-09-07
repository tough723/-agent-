package com.oncall.agent.report;

import com.oncall.agent.execute.ExecutionResult;
import com.oncall.domain.plan.BasisRef;
import com.oncall.domain.plan.Plan;
import com.oncall.domain.plan.PlanStep;
import com.oncall.domain.run.AgentRun;
import com.oncall.domain.run.RunStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 报告生成器 —— 把一次 run 的事实收敛成一份可被人复核的结论。
 *
 * <h2>★ 为什么这个类<b>不调用模型</b></h2>
 * <p>报告的<b>结论</b>必须能从 {@code RunStatus} 机械地推出来。
 * 让模型来写「这次处置成功了吗」，它完全可能在一次
 * {@code HANDED_OVER}（已交回人工）上写出「问题已解决」——
 * 那正是 {@code Executor} 已经堵过的同一类谎言：<b>跳过或交回却说成完成</b>。
 *
 * <p>所以本类刻意是纯函数式的：输入 run / plan / result，输出一份
 * 结论与输入<b>必然一致</b>的报告。「危险建议率」「引用幻觉率」这两个评测指标
 * 要有意义，前提就是报告本身不会撒谎。
 *
 * <p>摘要文字（自然语言那部分）可以之后由模型生成，
 * 但它只能是<b>对这份结论的复述</b>，不能改变结论。
 *
 * <h2>★ 为什么需要 {@code readOnlyTools}</h2>
 * <p>要区分「处置完了」（{@code RESOLVED}）和「查清了、确认不需要处置」
 * （{@code NO_ACTION_NEEDED}），就必须知道哪些工具是只读的。
 * 这两个结论<b>都是成功</b>，但合并它们会让「自动处置率」失去分母 ——
 * 把「只是看了看」算成「自动处置了」，是这个指标最典型的灌水方式。
 */
public final class Reporter {

    private final Set<String> readOnlyTools;

    /**
     * @param readOnlyTools 只读工具名集合。与 {@code Executor} 的白名单同源；
     *                      用 {@code Set.copyOf} 固定，防止调用方之后往里加一个
     *                      写工具，把一次真实的处置悄悄改判成「无需处置」。
     */
    public Reporter(Set<String> readOnlyTools) {
        Objects.requireNonNull(readOnlyTools, "readOnlyTools");
        this.readOnlyTools = Set.copyOf(readOnlyTools);
    }

    /**
     * 生成报告。
     *
     * @param run    终态的 run。<b>{@code RUNNING} 会被拒绝</b> ——
     *               一次还没结束的 run 不该有报告，否则「进行中」会被读成某种结论。
     * @param plan   最终执行的那份计划（重规划过则是重规划后的那份）
     * @param result 执行结果
     * @throws IllegalStateException run 仍处于 {@code RUNNING}
     */
    public Report report(AgentRun run, Plan plan, ExecutionResult result) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(result, "result");
        if (run.status() == RunStatus.RUNNING) {
            throw new IllegalStateException(
                    "run 仍处于 RUNNING，不能出报告——一次还没结束的 run 不该有结论，"
                            + "否则「进行中」会被下游读成某种已完成的状态");
        }

        Report.Conclusion conclusion = conclude(run, plan);
        List<BasisRef> citations = collectCitations(plan);
        return new Report(run, conclusion, citations, summarize(run, plan, result, conclusion));
    }

    /**
     * 由 {@code RunStatus} 推出结论。<b>这是 {@code RunStatus} 上的全函数</b> ——
     * 用 switch 且不留 default，将来给 {@code RunStatus} 加一个新值时，
     * 这里会在编译期或运行期立刻暴露，而不是安静地落到某个兜底分支上。
     */
    private Report.Conclusion conclude(AgentRun run, Plan plan) {
        return switch (run.status()) {
            case SUCCEEDED -> onlyReadOnlySteps(plan)
                    ? Report.Conclusion.NO_ACTION_NEEDED
                    : Report.Conclusion.RESOLVED;
            case HANDED_OVER -> Report.Conclusion.HANDED_OVER;
            case FAILED, ABORTED -> Report.Conclusion.FAILED;
            // RUNNING 已在 report() 里拒掉；这里再兜一次，
            // 免得将来有人绕过 report() 直接调本方法。
            case RUNNING -> throw new IllegalStateException("RUNNING 不能出报告");
        };
    }

    /** 计划里是否<b>全部</b>都是只读步骤。 */
    private boolean onlyReadOnlySteps(Plan plan) {
        for (PlanStep s : plan.steps()) {
            if (!readOnlyTools.contains(s.action())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 收集引用。<b>只收计划里真实声明过的 {@link BasisRef}</b>，
     * 不去「总结」出新的出处 —— 那正是引用幻觉的来源。
     *
     * <p>去重但保序：同一个依据被多步引用只算一次，
     * 但顺序保持计划顺序，便于人按步骤复核。
     */
    private static List<BasisRef> collectCitations(Plan plan) {
        List<BasisRef> out = new ArrayList<>();
        for (PlanStep s : plan.steps()) {
            for (BasisRef ref : s.basisRefs()) {
                if (!out.contains(ref)) {
                    out.add(ref);
                }
            }
        }
        return out;
    }

    /**
     * 摘要。<b>只陈述事实，不下判断</b> —— 判断已经在 {@code conclusion} 里了。
     *
     * <p>刻意把 {@code stoppedReason} 原样带出来：一份写着「已完成」却没说
     * 「第 3 步交回人工」的报告，会让人以为全流程都跑通了。
     */
    private static String summarize(AgentRun run, Plan plan, ExecutionResult result,
                                    Report.Conclusion conclusion) {
        StringBuilder sb = new StringBuilder();
        sb.append("结论=").append(conclusion)
                .append("；计划 ").append(plan.size()).append(" 步")
                .append("，已执行 ").append(result.stepsExecuted()).append(" 步")
                .append("；重规划 ").append(run.usedReplans())
                .append('/').append(run.budgetReplans()).append(" 次");
        if (result.stoppedReason() != null) {
            sb.append("；停止原因：").append(result.stoppedReason());
        }
        return sb.toString();
    }
}
