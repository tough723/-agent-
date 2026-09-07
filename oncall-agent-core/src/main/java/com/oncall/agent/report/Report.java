package com.oncall.agent.report;

import com.oncall.domain.plan.BasisRef;
import com.oncall.domain.run.AgentRun;

import java.util.List;
import java.util.Objects;

/**
 * 一次 run 的最终报告。
 *
 * <h2>★ 为什么结论由 {@link com.oncall.domain.run.RunStatus} 推出，而不是由模型写</h2>
 * <p>如果让模型来写「这次处置成功了吗」，它完全可能在一次
 * {@code HANDED_OVER}（已交回人工）上写出「问题已解决」——
 * 那正是本项目在 {@code Executor} 上已经堵过的同一类谎言：
 * <b>跳过或交回却说成完成</b>。
 *
 * <p>所以 {@link Conclusion} 是 {@code RunStatus} 上的一个<b>全函数</b>：
 * 每个状态都有唯一对应的结论，{@code RUNNING} 直接拒绝（一次还没结束的 run
 * 不该有报告）。模型可以参与写「摘要文字」，但<b>不能参与决定结论</b>。
 *
 * <h2>★ 为什么「无需处置」是一个显式结论</h2>
 * <p>{@code Plan} 的构造期不变量拒绝空计划，理由就写在那儿：
 * 「无需处置」应当是 Reporter 的<b>显式结论</b>，而不是一份空计划。
 * 空计划会让「查了，确认没事」和「什么都没查」在数据上长得一模一样 ——
 * 而这正是自动处置率这个指标最容易被灌水的地方。
 *
 * <h2>★ 为什么引用必须是 {@link BasisRef} 而不是自由文本</h2>
 * <p>「引用幻觉率」这个评测指标要能算，前提是引用<b>可追溯到具体出处</b>。
 * 自由文本的引用（「根据相关文档」）根本无法判定真伪，
 * 于是那个指标就永远只能填 0 —— 而填 0 不等于没有幻觉。
 */
public record Report(
        AgentRun run,
        Conclusion conclusion,
        List<BasisRef> citations,
        String summary) {

    /**
     * 报告结论。与 {@code RunStatus} 一一对应，另加一个「无需处置」。
     */
    public enum Conclusion {
        /** 计划全部执行完毕，且其中包含真正改变系统状态的动作。 */
        RESOLVED,
        /**
         * 计划全部执行完毕，但<b>全程只有只读探查</b> —— 查清了，确认不需要处置。
         *
         * <p>这是一个<b>成功</b>结论，不是「什么都没做」。
         * 把它和 {@link #RESOLVED} 合并会让「自动处置率」失去分母。
         */
        NO_ACTION_NEEDED,
        /** 放权不足或触及审批，已交回人工。这是正常出口，不是故障。 */
        HANDED_OVER,
        /** 执行失败或被中止。 */
        FAILED
    }

    public Report {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(conclusion, "conclusion");
        Objects.requireNonNull(citations, "citations");
        Objects.requireNonNull(summary, "summary");
        // List.copyOf 而不是直接赋值：否则调用方之后往这个 list 里 add 一条
        // 「可信依据」，报告就凭空多了一个从未存在过的引用。
        citations = List.copyOf(citations);
        if (summary.isBlank()) {
            throw new IllegalArgumentException(
                    "summary 不能为空——一份没有结论说明的报告无法被人复核");
        }
    }

    /** 这份报告是否代表「机器自己处置完了」，用于自动处置率的分子。 */
    public boolean autoResolved() {
        return conclusion == Conclusion.RESOLVED;
    }
}
