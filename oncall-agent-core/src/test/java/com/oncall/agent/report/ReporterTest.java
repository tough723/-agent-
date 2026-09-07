package com.oncall.agent.report;

import com.oncall.agent.execute.ExecutionResult;
import com.oncall.domain.autonomy.AutonomyLevel;
import com.oncall.domain.plan.BasisRef;
import com.oncall.domain.plan.BasisType;
import com.oncall.domain.plan.Plan;
import com.oncall.domain.plan.PlanStep;
import com.oncall.domain.run.AgentRun;
import com.oncall.domain.run.RunStatus;
import com.oncall.domain.trace.TraceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Reporter} 的验收测试。
 *
 * <p>★ 这里最关键的一条是
 * {@link #handedOverIsNeverReportedAsResolved()}：
 * 一次「交回人工」的执行<b>绝不能</b>被报告成「已解决」。
 */
class ReporterTest {

    private static final String READ = "k8s_get_pods";
    private static final String HIGH = "scale_replicas";
    private static final Instant T0 = Instant.parse("2026-09-07T00:00:00Z");

    private static final BasisRef RULE = new BasisRef(BasisType.ALERT_RULE, "rule-1");
    private static final BasisRef BOOK = new BasisRef(BasisType.RUNBOOK, "rb-1");

    private static Reporter reporter() {
        return new Reporter(Set.of(READ, "k8s_get_events", "k8s_get_logs"));
    }

    private static AgentRun run(RunStatus status) {
        AgentRun r = AgentRun.start("run-rep", TraceId.adopt("oc-trace-rep"), "grp-1",
                AutonomyLevel.BOUNDED_AUTO, 10, 100_000L, new BigDecimal("5.000000"), 2, T0);
        return status == RunStatus.RUNNING ? r : r.finish(status, T0);
    }

    private static PlanStep step(int seq, String action, BasisRef... refs) {
        return new PlanStep(seq, action, "{}", List.of(refs));
    }

    /** 全只读的计划。 */
    private static Plan readOnlyPlan() {
        return Plan.of(step(1, READ, RULE), step(2, "k8s_get_events", RULE),
                step(3, "k8s_get_logs", BOOK));
    }

    /** 含一个高危写操作的计划。 */
    private static Plan planWithAWrite() {
        return Plan.of(step(1, READ, RULE), step(2, "k8s_get_events", RULE),
                step(3, HIGH, BOOK));
    }

    private static ExecutionResult result(AgentRun run, int steps, String stopped) {
        return new ExecutionResult(run, steps, stopped);
    }

    // ------------------------------------------------------- 结论必须说实话

    /**
     * ★ 本类最重要的一条。
     *
     * <p>如果结论由模型来写，它完全可能在一次 {@code HANDED_OVER} 上写出
     * 「问题已解决」—— 那正是 {@code Executor} 已经堵过的同一类谎言：
     * <b>跳过或交回却说成完成</b>。所以结论必须从 {@code RunStatus} 机械推出。
     */
    @Test
    @DisplayName("★ 交回人工绝不会被报告成已解决")
    void handedOverIsNeverReportedAsResolved() {
        AgentRun run = run(RunStatus.HANDED_OVER);
        // 注意：计划里明明有高危写操作，而且前两步都跑完了 ——
        // 一个「看起来进展不错」的 run。结论仍然必须是 HANDED_OVER。
        Report report = reporter().report(run, planWithAWrite(),
                result(run, 2, "高危动作需人工确认"));

        assertThat(report.conclusion()).isEqualTo(Report.Conclusion.HANDED_OVER);
        assertThat(report.autoResolved())
                .as("交回人工不能算进自动处置率的分子")
                .isFalse();
        assertThat(report.summary()).as("停止原因必须出现在摘要里")
                .contains("高危动作需人工确认");
    }

    @Test
    @DisplayName("成功且含真实写操作 → RESOLVED")
    void succeededWithAWriteIsResolved() {
        AgentRun run = run(RunStatus.SUCCEEDED);
        Report report = reporter().report(run, planWithAWrite(), result(run, 3, null));

        assertThat(report.conclusion()).isEqualTo(Report.Conclusion.RESOLVED);
        assertThat(report.autoResolved()).isTrue();
    }

    /**
     * ★ 「查清了、确认不需要处置」是一个<b>成功</b>结论，不是「什么都没做」。
     *
     * <p>把它和 {@code RESOLVED} 合并会让「自动处置率」失去分母 ——
     * 把「只是看了看」算成「自动处置了」，是这个指标最典型的灌水方式。
     */
    @Test
    @DisplayName("★ 成功但全程只读 → NO_ACTION_NEEDED（与 RESOLVED 分开）")
    void succeededWithOnlyReadsIsNoActionNeeded() {
        AgentRun run = run(RunStatus.SUCCEEDED);
        Report report = reporter().report(run, readOnlyPlan(), result(run, 3, null));

        assertThat(report.conclusion()).isEqualTo(Report.Conclusion.NO_ACTION_NEEDED);
        assertThat(report.autoResolved())
                .as("「无需处置」不算自动处置，否则分母被灌水")
                .isFalse();
    }

    @Test
    @DisplayName("FAILED 与 ABORTED 都归为 FAILED 结论")
    void failedAndAbortedBothConcludeFailed() {
        for (RunStatus st : List.of(RunStatus.FAILED, RunStatus.ABORTED)) {
            AgentRun run = run(st);
            assertThat(reporter().report(run, planWithAWrite(), result(run, 1, "工具超时"))
                    .conclusion())
                    .as("状态 %s", st)
                    .isEqualTo(Report.Conclusion.FAILED);
        }
    }

    /**
     * ★ 结论是 {@code RunStatus} 上的<b>全函数</b>：5 个状态一个不漏。
     *
     * <p>这条测试是「枚举分支必须逐一断言」这条教训的直接落地 ——
     * 本项目已经因为测试夹具只覆盖 {@code LOW}/{@code HIGH} 两个值，
     * 让一个把 {@code READ_ONLY} 判反的 {@code AutonomyGate} 绿着上线过。
     */
    @Test
    @DisplayName("★ RunStatus 的 5 个值全部有明确结论，RUNNING 被拒")
    void everyRunStatusHasExactlyOneConclusion() {
        assertThat(RunStatus.values()).hasSize(5);

        AgentRun running = run(RunStatus.RUNNING);
        assertThatThrownBy(() -> reporter().report(running, readOnlyPlan(),
                result(running, 1, "还没结束")))
                .as("一次还没结束的 run 不该有报告")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");

        for (RunStatus st : List.of(RunStatus.SUCCEEDED, RunStatus.FAILED,
                RunStatus.ABORTED, RunStatus.HANDED_OVER)) {
            AgentRun r = run(st);
            String stopped = st == RunStatus.SUCCEEDED ? null : "原因";
            assertThat(reporter().report(r, planWithAWrite(), result(r, 2, stopped)).conclusion())
                    .as("状态 %s 必须能出结论", st)
                    .isNotNull();
        }
    }

    // ---------------------------------------------------------------- 引用

    @Test
    @DisplayName("引用只来自计划里真实声明过的 basis，去重且保序")
    void citationsComeOnlyFromThePlanAndAreDeduplicatedInOrder() {
        AgentRun run = run(RunStatus.SUCCEEDED);
        // RULE 被第 1、2 步重复引用；BOOK 在第 3 步。
        Report report = reporter().report(run, readOnlyPlan(), result(run, 3, null));

        assertThat(report.citations()).containsExactly(RULE, BOOK);
    }

    @Test
    @DisplayName("★ 报告里的引用列表不可被调用方事后追加")
    void citationsListIsImmutable() {
        AgentRun run = run(RunStatus.SUCCEEDED);
        Report report = reporter().report(run, readOnlyPlan(), result(run, 3, null));

        // 否则调用方往这个 list 里 add 一条「可信依据」，
        // 报告就凭空多了一个从未存在过的引用 —— 而引用幻觉率会因此看起来更好。
        assertThatThrownBy(() -> report.citations().add(new BasisRef(BasisType.RUNBOOK, "编的")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("Report 构造期拒绝空摘要")
    void reportRejectsABlankSummary() {
        AgentRun run = run(RunStatus.SUCCEEDED);
        assertThatThrownBy(() -> new Report(run, Report.Conclusion.RESOLVED, List.of(RULE), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("summary");
    }

    @Test
    @DisplayName("摘要带上重规划次数与已执行步数，便于人复核")
    void summaryCarriesTheFactsNeededForReview() {
        // ★ 顺序就是真实循环里的顺序：执行失败 → 重规划（终态回到 RUNNING）→ 再执行成功。
        AgentRun run = run(RunStatus.FAILED).resumeForReplan().finish(RunStatus.SUCCEEDED, T0);
        Report report = reporter().report(run, planWithAWrite(), result(run, 3, null));

        assertThat(report.summary())
                .contains("计划 3 步")
                .contains("已执行 3 步")
                .contains("重规划 1/2 次");
        assertThat(report.summary()).as("成功时不该有停止原因")
                .doesNotContain("停止原因");
    }

    // ------------------------------------------------------------ 防御性拷贝

    /**
     * ★ 传进来的集合必须被固定。
     *
     * <p>否则调用方在构造之后往那个 set 里加一个写工具，
     * 就能把一次<b>真实的处置</b>悄悄改判成「无需处置」——
     * 而自动处置率会因此变好看。
     */
    @Test
    @DisplayName("★ readOnlyTools 被防御性拷贝：事后修改原集合不影响判定")
    void readOnlyToolsAreCopiedDefensively() {
        Set<String> mutable = new HashSet<>(Set.of(READ, "k8s_get_events", "k8s_get_logs"));
        Reporter r = new Reporter(mutable);
        mutable.add(HIGH);   // 事后把一个高危写工具塞进「只读」集合

        AgentRun run = run(RunStatus.SUCCEEDED);
        assertThat(r.report(run, planWithAWrite(), result(run, 3, null)).conclusion())
                .as("把写工具伪装成只读，不能把 RESOLVED 改判成 NO_ACTION_NEEDED")
                .isEqualTo(Report.Conclusion.RESOLVED);
    }

    @Test
    @DisplayName("入参守卫：null 一律拒绝")
    void rejectsNulls() {
        AgentRun run = run(RunStatus.SUCCEEDED);
        Reporter r = reporter();
        assertThatThrownBy(() -> r.report(null, readOnlyPlan(), result(run, 3, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> r.report(run, null, result(run, 3, null)))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> r.report(run, readOnlyPlan(), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Reporter(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Report 构造期就固定引用列表（不依赖调用方传不可变列表）")
    void reportCopiesTheCitationListAtConstruction() {
        AgentRun run = run(RunStatus.SUCCEEDED);
        List<BasisRef> mutable = new ArrayList<>(List.of(RULE));
        Report report = new Report(run, Report.Conclusion.RESOLVED, mutable, "摘要");
        mutable.add(BOOK);   // 构造之后再改原列表

        assertThat(report.citations())
                .as("构造后修改原列表不得影响报告")
                .containsExactly(RULE);
    }
}
