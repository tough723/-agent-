package com.oncall.agent.plan;

import com.oncall.agent.execute.ExecutionResult;
import com.oncall.agent.llm.StubChatModel;
import com.oncall.agent.prompt.ActiveVersionSource;
import com.oncall.agent.prompt.PromptRegistry;
import com.oncall.domain.autonomy.AutonomyLevel;
import com.oncall.domain.plan.BasisType;
import com.oncall.domain.plan.Plan;
import com.oncall.domain.run.AgentRun;
import com.oncall.domain.run.RunStatus;
import com.oncall.domain.trace.TraceId;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.toolgateway.ToolPolicyEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Replanner} 的验收测试。
 *
 * <p>★ 这里最关键的一条不是「能产出新计划」，而是
 * <b>「产出的同时预算一定被扣掉了」</b> —— 见
 * {@link #replanConsumesTheBudgetAndReturnsTheDecrementedRun()}。
 */
class ReplannerTest {

    private static final String HIGH = "scale_replicas";
    private static final Instant T0 = Instant.parse("2026-09-07T00:00:00Z");

    /** 三步只读探查，全部有可信依据 —— 能通过 PlanValidator。 */
    private static final String OK_JSON = """
            {"steps":[
              {"seq":1,"action":"k8s_get_events","args":{},
               "basis":[{"type":"ALERT_RULE","ref":"rule-1"}]},
              {"seq":2,"action":"k8s_get_logs","args":{},
               "basis":[{"type":"ALERT_RULE","ref":"rule-1"}]},
              {"seq":3,"action":"k8s_get_pods","args":{"service":"payment-api"},
               "basis":[{"type":"RUNBOOK","ref":"rb-1"}]}
            ]}""";

    private static Replanner replanner(StubChatModel model) {
        PromptRegistry prompts = PromptRegistry.fromClasspath(
                List.of("replan-generate"),
                ActiveVersionSource.fixed(Map.of("replan-generate", "v1")));
        ToolPolicyEngine engine = new ToolPolicyEngine(List.of(
                ToolPolicy.readOnly("k8s_get_pods"),
                ToolPolicy.readOnly("k8s_get_events"),
                ToolPolicy.readOnly("k8s_get_logs"),
                ToolPolicy.highRisk(HIGH, Duration.ofMinutes(10))));
        return new Replanner(model, prompts, new PlanValidator(engine, 3));
    }

    private static List<String> tools() {
        return List.of("k8s_get_pods", "k8s_get_events", "k8s_get_logs", HIGH);
    }

    /** 一个 RUNNING 且还有重规划预算的 run。 */
    private static AgentRun running(int budgetReplans) {
        return AgentRun.start("run-rp", TraceId.adopt("oc-trace-rp"), "grp-1",
                AutonomyLevel.BOUNDED_AUTO, 10, 100_000L, new BigDecimal("5.000000"),
                budgetReplans, T0);
    }

    /** 一次没跑完的执行结果（FAILED + 停止原因）。 */
    private static ExecutionResult failed(AgentRun run) {
        return new ExecutionResult(run.finish(RunStatus.FAILED, T0), 1, "第 2 步工具超时");
    }

    // --------------------------------------------------------------- 正常路径

    @Test
    @DisplayName("正常重规划：产出新计划，且 prompt 真的渲染进了失败原因")
    void producesANewPlanAndRendersTheFailureReason() {
        StubChatModel model = StubChatModel.returning(OK_JSON);
        AgentRun run = running(2);

        ReplanOutcome outcome = replanner(model).replan(run, "payment-api P99 延迟升高",
                failed(run), tools());

        assertThat(outcome.plan().size()).isEqualTo(3);
        assertThat(outcome.plan().step(1).action()).isEqualTo("k8s_get_events");
        assertThat(outcome.plan().step(3).basisRefs().get(0).type()).isEqualTo(BasisType.RUNBOOK);

        String sent = model.lastPrompt();
        // ★ 失败原因是重规划的核心输入。没渲染进去，重规划就只是在瞎猜。
        assertThat(sent).as("失败原因必须渲染进 prompt").contains("第 2 步工具超时");
        assertThat(sent).as("已执行步数必须渲染进 prompt").contains("1");
        assertThat(sent).as("原始告警必须渲染进 prompt").contains("payment-api P99 延迟升高");
        assertThat(sent).as("工具清单必须渲染进 prompt").contains("k8s_get_events");
    }

    /**
     * ★ 本类最重要的一条。
     *
     * <p>{@code consumeReplan()} 返回的是一个<b>新的</b>不可变 run。
     * 如果 {@code Replanner} 只返回 {@link Plan}，调用方一旦忘记自己扣预算，
     * {@code used_replans} 就永远停在 0 —— 那个预算等于不存在，
     * 循环可以无限改主意而每一步看起来都合法。
     */
    @Test
    @DisplayName("★ 重规划必定扣预算：返回的 run.usedReplans 已经 +1")
    void replanConsumesTheBudgetAndReturnsTheDecrementedRun() {
        AgentRun run = running(2);
        assertThat(run.usedReplans()).isEqualTo(0);

        ReplanOutcome outcome = replanner(StubChatModel.returning(OK_JSON))
                .replan(run, "告警", failed(run), tools());

        assertThat(outcome.run().usedReplans())
                .as("返回的 run 必须是扣过预算的那个，不是传进去的旧 run")
                .isEqualTo(1);
        assertThat(outcome.run().budgetReplans()).isEqualTo(2);
        // 传进去的 run 不该被改动（record 不可变）——
        // 这条断言保证「扣预算」不是靠原地修改实现的。
        assertThat(run.usedReplans()).as("原 run 必须保持不变").isEqualTo(0);
    }

    @Test
    @DisplayName("连续重规划会把预算一路扣到耗尽")
    void successiveReplansDrainTheBudget() {
        Replanner r = replanner(StubChatModel.returning(OK_JSON, OK_JSON));
        AgentRun run = running(2);

        ReplanOutcome first = r.replan(run, "告警", failed(run), tools());
        assertThat(first.run().usedReplans()).isEqualTo(1);
        assertThat(first.run().replanBudgetExhausted()).isFalse();

        ReplanOutcome second = r.replan(first.run(), "告警", failed(first.run()), tools());
        assertThat(second.run().usedReplans()).isEqualTo(2);
        assertThat(second.run().replanBudgetExhausted())
                .as("2/2 之后必须判定为耗尽，否则第三次还能继续")
                .isTrue();
    }

    // ------------------------------------------------- 三种「根本不该重规划」

    @Test
    @DisplayName("★ 上一次已成功：不许重规划（没有可重规划的东西，只会白扣预算）")
    void refusesWhenTheLastRunSucceeded() {
        AgentRun run = running(2);
        ExecutionResult done = new ExecutionResult(run.finish(RunStatus.SUCCEEDED, T0), 3, null);

        assertThatThrownBy(() -> replanner(StubChatModel.returning(OK_JSON))
                .replan(run, "告警", done, tools()))
                .isInstanceOf(ReplanNotApplicableException.class)
                .hasMessageContaining("SUCCEEDED");
    }

    @Test
    @DisplayName("★ 上一次已交回人工：不许重规划（人已经接手，机器再改主意是越权）")
    void refusesWhenHandedOverToAHuman() {
        AgentRun run = running(2);
        ExecutionResult handed = new ExecutionResult(
                run.finish(RunStatus.HANDED_OVER, T0), 1, "高危动作需人工确认");

        assertThatThrownBy(() -> replanner(StubChatModel.returning(OK_JSON))
                .replan(run, "告警", handed, tools()))
                .isInstanceOf(ReplanNotApplicableException.class)
                .hasMessageContaining("HANDED_OVER");
    }

    /**
     * ★ 这一条正是 {@code budget_replans} 这一列存在的理由。
     *
     * <p>预算为 0 是<b>合法配置</b>（「按最初计划一路走到底」是一种正当策略），
     * 此时第一次重规划就必须被拒 —— 否则那个预算形同虚设。
     */
    @Test
    @DisplayName("★ 重规划预算为 0：第一次重规划就被拒（这正是 budget_replans 的意义）")
    void refusesWhenReplanBudgetIsZero() {
        AgentRun run = running(0);
        assertThat(run.replanBudgetExhausted()).as("0/0 应判定为已耗尽").isTrue();

        assertThatThrownBy(() -> replanner(StubChatModel.returning(OK_JSON))
                .replan(run, "告警", failed(run), tools()))
                .isInstanceOf(ReplanNotApplicableException.class)
                .hasMessageContaining("预算已耗尽");
    }

    @Test
    @DisplayName("预算耗尽时抛的是 ReplanNotApplicable，不是模型可用性异常")
    void budgetExhaustionIsNotReportedAsAModelFailure() {
        AgentRun run = running(0);
        // ★ 两种异常分开：预算耗尽是「容量」问题，模型挂了是「依赖」问题。
        //   合并成一个计数会把容量规划问题误读成稳定性事故。
        assertThatThrownBy(() -> replanner(StubChatModel.returning(OK_JSON))
                .replan(run, "告警", failed(run), tools()))
                .isInstanceOf(ReplanNotApplicableException.class)
                .isNotInstanceOf(PlanProductionException.class);
    }

    // --------------------------------------------------- 与 Planner 共用校验

    /**
     * ★ 重规划<b>不享有更宽松的校验标准</b>。
     *
     * <p>{@code Replanner} 与 {@code Planner} 共用同一个 {@link PlanValidator}
     * 和同一段 {@code PlanParser}。如果重规划产出的计划能绕过校验，
     * 那么「原计划不许做的事，重规划就能做」，闸门形同虚设。
     */
    @Test
    @DisplayName("★ 重规划的计划走同一套校验：缺 basis 照样被拒")
    void replannedPlanGoesThroughTheSameValidator() {
        String noBasis = """
                {"steps":[
                  {"seq":1,"action":"k8s_get_events","args":{}},
                  {"seq":2,"action":"k8s_get_logs","args":{}},
                  {"seq":3,"action":"k8s_get_pods","args":{}}
                ]}""";
        AgentRun run = running(2);

        assertThatThrownBy(() -> replanner(StubChatModel.returning(noBasis))
                .replan(run, "告警", failed(run), tools()))
                .isInstanceOf(PlanProductionException.class)
                .hasMessageContaining("basis");
    }

    @Test
    @DisplayName("★ 重规划的计划走同一套校验：高危动作前置只读不足照样被拒")
    void replannedPlanStillNeedsInvestigationBeforeHighRisk() {
        // 三步全是高危写操作，前面没有任何只读探查。
        String allWrites = """
                {"steps":[
                  {"seq":1,"action":"scale_replicas","args":{},
                   "basis":[{"type":"ALERT_RULE","ref":"rule-1"}]},
                  {"seq":2,"action":"scale_replicas","args":{},
                   "basis":[{"type":"ALERT_RULE","ref":"rule-1"}]},
                  {"seq":3,"action":"scale_replicas","args":{},
                   "basis":[{"type":"ALERT_RULE","ref":"rule-1"}]}
                ]}""";
        AgentRun run = running(2);

        assertThatThrownBy(() -> replanner(StubChatModel.returning(allWrites))
                .replan(run, "告警", failed(run), tools()))
                .isInstanceOf(PlanRejectedException.class);
    }

    // ------------------------------------------------------------- 入参守卫

    @Test
    @DisplayName("入参守卫：空告警 / 空工具清单 / null 一律拒绝")
    void rejectsBadArguments() {
        Replanner r = replanner(StubChatModel.returning(OK_JSON));
        AgentRun run = running(2);

        assertThatThrownBy(() -> r.replan(run, "  ", failed(run), tools()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alert");
        assertThatThrownBy(() -> r.replan(run, "告警", failed(run), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availableTools");
        assertThatThrownBy(() -> r.replan(run, "告警", null, tools()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("模型不可用：抛 PlanProductionException，不做兜底")
    void modelFailurePropagatesWithoutFallback() {
        AgentRun run = running(2);
        // ★ 刻意不兜底：「模型没给出计划就沿用上一个计划再跑一遍」不是兜底，
        //   是死循环——上一个计划刚刚失败过。
        assertThatThrownBy(() -> replanner(StubChatModel.returning(""))
                .replan(run, "告警", failed(run), tools()))
                .isInstanceOf(PlanProductionException.class);
    }

    // ------------------------------------------------------- ReplanOutcome

    @Test
    @DisplayName("★ ReplanOutcome 构造期就拒绝「没扣过预算的 run」")
    void outcomeRejectsARunThatWasNotDecremented() {
        AgentRun fresh = running(2);   // usedReplans == 0
        Plan plan = replanner(StubChatModel.returning(OK_JSON))
                .replan(fresh, "告警", failed(fresh), tools()).plan();

        // 把「新计划 + 扣之前的旧 run」拼在一起 —— 这正是调用方忘记扣预算时会发生的事。
        assertThatThrownBy(() -> new ReplanOutcome(fresh, plan))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("usedReplans");
    }

    /**
     * ★ 空计划在 {@link Plan} 的构造期就被拒，<b>轮不到 {@code ReplanOutcome}</b>。
     *
     * <p>这条测试断言的是那个真正生效的不变量。之前这里写的是
     * 「ReplanOutcome 拒绝空计划」，但异常其实是 {@code Plan} 抛的 ——
     * 一条名字与实测对象不符的测试，比没有测试更容易骗人。
     */
    @Test
    @DisplayName("空计划由 Plan 构造器拒绝（轮不到 ReplanOutcome）")
    void emptyPlanIsRejectedByThePlanConstructor() {
        assertThatThrownBy(() -> new Plan(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无需处置");
    }
}
