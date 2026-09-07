package com.oncall.agent.execute;

import com.oncall.agent.run.AgentStepStore;
import com.oncall.domain.autonomy.AlertSeverity;
import com.oncall.domain.autonomy.AutonomyLevel;
import com.oncall.domain.plan.BasisRef;
import com.oncall.domain.plan.Plan;
import com.oncall.domain.plan.PlanStep;
import com.oncall.domain.run.AgentRun;
import com.oncall.domain.run.AgentStep;
import com.oncall.domain.run.RunStatus;
import com.oncall.domain.run.StepStatus;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.trace.TraceId;
import com.oncall.toolgateway.KillSwitch;
import com.oncall.toolgateway.RunMode;
import com.oncall.toolgateway.ToolPolicyEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link Executor} 的契约。
 *
 * <h2>★ 本类的验收断言</h2>
 * <p>{@link #perStepGatingKeepsReadOnlyFactsAndHandsOverAtTheWrite()}。
 * 它守的是本轮的核心设计：<b>放权判定逐步做，不做整轮</b>——
 * 只读探查照常执行并留下事实，遇到不能自动执行的写操作才交回人工。
 *
 * <p>若有人把判定挪到循环外（整轮判一次），这条会红：
 * 整轮拒绝会让 {@code stepsExecuted} 变成 0，已查到的事实全丢。
 */
@DisplayName("Executor：逐步放权、交回人工而不是跳过、预算先判再花")
class ExecutorTest {

    private static final Instant T0 = Instant.parse("2026-09-07T03:00:00Z");
    private static final Clock FIXED = Clock.fixed(T0, ZoneOffset.UTC);

    private static final String READ_PODS = "k8s_get_pods";
    private static final String READ_LOGS = "k8s_get_logs";
    private static final String WRITE = "scale_replicas";

    // ── 替身 ────────────────────────────────────────────────

    /** 内存版 AgentStepStore。记录落库顺序，供断言「事实被保留」。 */
    private static final class MemStepStore implements AgentStepStore {
        final Map<String, AgentStep> byId = new HashMap<>();
        final List<AgentStep> finished = new ArrayList<>();
        /** 设为 true 时模拟「这一步已被别的 worker 抢走」。 */
        boolean alreadyClaimed;

        @Override
        public boolean tryInsert(AgentStep step) {
            if (alreadyClaimed || byId.containsKey(step.id())) {
                return false;
            }
            byId.put(step.id(), step);
            return true;
        }

        @Override
        public Optional<AgentStep> findById(String id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<AgentStep> findByRunId(String runId) {
            return byId.values().stream().filter(s -> s.runId().equals(runId)).toList();
        }

        @Override
        public void finish(AgentStep step) {
            byId.put(step.id(), step);
            finished.add(step);
        }
    }

    private static final class StubTool implements ToolCallback {
        private final String name;
        private final String result;
        private final RuntimeException boom;
        int calls;

        StubTool(String name, String result) {
            this(name, result, null);
        }

        StubTool(String name, String result, RuntimeException boom) {
            this.name = name;
            this.result = result;
            this.boom = boom;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder().name(name)
                    .description("测试替身 " + name)
                    .inputSchema("{\"type\":\"object\"}").build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            if (boom != null) {
                throw boom;
            }
            return result;
        }
    }

    // ── 组装 ────────────────────────────────────────────────

    private static ToolPolicyEngine engine() {
        return new ToolPolicyEngine(List.of(
                ToolPolicy.readOnly(READ_PODS),
                ToolPolicy.readOnly(READ_LOGS),
                ToolPolicy.highRisk(WRITE, Duration.ofMinutes(10))));
    }

    private static PlanStep read(int seq, String action) {
        return new PlanStep(seq, action, "{}", List.of(BasisRef.alertRule("rule-1")));
    }

    private static PlanStep write(int seq) {
        return new PlanStep(seq, WRITE, "{\"replicas\":7}",
                List.of(BasisRef.alertRule("rule-1")));
    }

    private static AgentRun run(AutonomyLevel level, int budgetSteps) {
        return AgentRun.start("run-1", TraceId.adopt("oc-trace-1"), "grp-1", level,
                budgetSteps, 100_000L, new BigDecimal("5.0"), T0);
    }

    private record Fixture(Executor executor, MemStepStore store, StubTool pods,
                           StubTool logs, StubTool scale, KillSwitch killSwitch) {}

    private static Fixture fixture(AutonomyLevel level, Set<String> whitelist, RunMode mode) {
        MemStepStore store = new MemStepStore();
        StubTool pods = new StubTool(READ_PODS, "3 pods running");
        StubTool logs = new StubTool(READ_LOGS, "OOMKilled at 02:58");
        StubTool scale = new StubTool(WRITE, "scaled to 7");
        Map<String, ToolCallback> tools = new HashMap<>();
        tools.put(READ_PODS, pods);
        tools.put(READ_LOGS, logs);
        tools.put(WRITE, scale);
        KillSwitch ks = new KillSwitch();
        ks.set(mode);
        Executor ex = new Executor(engine(), n -> Optional.ofNullable(tools.get(n)), store, ks,
                (runId, step, toolName, args) -> runId + "|" + step + "|" + toolName,
                whitelist, FIXED);
        return new Fixture(ex, store, pods, logs, scale, ks);
    }

    // ── ★ 验收断言 ──────────────────────────────────────────

    @Test
    @DisplayName("★★ 逐步放权：只读照常执行并留下事实，遇到写操作才交回人工")
    void perStepGatingKeepsReadOnlyFactsAndHandsOverAtTheWrite() {
        // READ_ONLY 不在自动白名单里也没关系——它在 BOUNDED_AUTO 下本就允许；
        // 关键是被测的是「写操作那一步停下」，而不是「整轮被拒」。
        Fixture f = fixture(AutonomyLevel.BOUNDED_AUTO, Set.of(READ_PODS, READ_LOGS), RunMode.FULL);
        Plan plan = Plan.of(read(1, READ_PODS), read(2, READ_LOGS), write(3));

        ExecutionResult r = f.executor.execute(run(AutonomyLevel.BOUNDED_AUTO, 10), plan,
                AlertSeverity.P2);

        assertThat(r.handedOver()).as("遇到高危写操作应交回人工").isTrue();
        assertThat(r.run().status()).isEqualTo(RunStatus.HANDED_OVER);
        assertThat(r.stepsExecuted()).as("前两步只读探查必须已经执行").isEqualTo(2);
        assertThat(r.stoppedReason()).contains("第 3 步").contains(WRITE);

        // ★ 已查到的事实被保留，不是白跑
        assertThat(f.pods.calls).isEqualTo(1);
        assertThat(f.logs.calls).isEqualTo(1);
        assertThat(f.scale.calls).as("高危工具绝不能被调用").isZero();
        assertThat(f.store.finished).hasSize(2);
        assertThat(f.store.finished).allSatisfy(s ->
                assertThat(s.status()).isEqualTo(StepStatus.SUCCEEDED));
        assertThat(f.store.finished.get(0).resultSummary()).contains("3 pods running");
    }

    @Test
    @DisplayName("★ SHADOW 级别：连只读也不自动执行，第 1 步就交回人工")
    void shadowHandsOverAtTheFirstStep() {
        Fixture f = fixture(AutonomyLevel.SHADOW, Set.of(READ_PODS, READ_LOGS), RunMode.FULL);
        ExecutionResult r = f.executor.execute(run(AutonomyLevel.SHADOW, 10),
                Plan.of(read(1, READ_PODS)), AlertSeverity.P2);

        assertThat(r.handedOver()).isTrue();
        assertThat(r.stepsExecuted()).isZero();
        assertThat(f.pods.calls).as("SHADOW 下一个工具都不该被调用").isZero();
    }

    @Test
    @DisplayName("全只读 + BOUNDED_AUTO：跑完并 SUCCEEDED")
    void allReadOnlyCompletes() {
        Fixture f = fixture(AutonomyLevel.BOUNDED_AUTO, Set.of(READ_PODS, READ_LOGS), RunMode.FULL);
        ExecutionResult r = f.executor.execute(run(AutonomyLevel.BOUNDED_AUTO, 10),
                Plan.of(read(1, READ_PODS), read(2, READ_LOGS)), AlertSeverity.P2);

        assertThat(r.completed()).isTrue();
        assertThat(r.stoppedReason()).isNull();
        assertThat(r.stepsExecuted()).isEqualTo(2);
        assertThat(r.run().usedSteps()).isEqualTo(2);
    }

    @Test
    @DisplayName("★ 预算先判再花：预算 1 步时第 2 步之前停下，不允许超支一步")
    void budgetIsCheckedBeforeSpending() {
        Fixture f = fixture(AutonomyLevel.BOUNDED_AUTO, Set.of(READ_PODS, READ_LOGS), RunMode.FULL);
        ExecutionResult r = f.executor.execute(run(AutonomyLevel.BOUNDED_AUTO, 1),
                Plan.of(read(1, READ_PODS), read(2, READ_LOGS)), AlertSeverity.P2);

        assertThat(r.run().status()).isEqualTo(RunStatus.ABORTED);
        assertThat(r.stepsExecuted()).isEqualTo(1);
        assertThat(f.logs.calls).as("预算耗尽后不该再调用工具").isZero();
        assertThat(r.stoppedReason()).contains("预算耗尽");
    }

    @Test
    @DisplayName("★ 工具不在白名单 → FAILED，且说清是哪个工具")
    void unlistedToolFails() {
        Fixture f = fixture(AutonomyLevel.BOUNDED_AUTO, Set.of(READ_PODS), RunMode.FULL);
        Plan plan = Plan.of(new PlanStep(1, "rm_rf_everything", "{}",
                List.of(BasisRef.alertRule("r"))));
        ExecutionResult r = f.executor.execute(run(AutonomyLevel.BOUNDED_AUTO, 10), plan,
                AlertSeverity.P2);

        assertThat(r.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.stoppedReason()).contains("rm_rf_everything").contains("白名单");
    }

    @Test
    @DisplayName("工具未装配 → FAILED（没装配 ≠ 不许执行）")
    void unassembledToolFails() {
        MemStepStore store = new MemStepStore();
        KillSwitch ks = new KillSwitch();
        // 白名单允许、策略是只读，但 tools 里根本没有这个回调
        Executor ex = new Executor(engine(), name -> Optional.empty(), store, ks,
                (a, b, c, d) -> a + "|" + b, Set.of(READ_PODS), FIXED);
        ExecutionResult r = ex.execute(run(AutonomyLevel.BOUNDED_AUTO, 10),
                Plan.of(read(1, READ_PODS)), AlertSeverity.P2);

        assertThat(r.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(r.stoppedReason()).contains("未被装配");
    }

    @Test
    @DisplayName("★ 工具抛异常：这一步标 FAILED，且失败原因落库")
    void toolFailureIsRecordedOnTheStep() {
        MemStepStore store = new MemStepStore();
        StubTool bad = new StubTool(READ_PODS, null, new IllegalStateException("K8s API 403"));
        Map<String, ToolCallback> tools = Map.of(READ_PODS, bad);
        Executor ex = new Executor(engine(), n -> Optional.ofNullable(tools.get(n)), store,
                new KillSwitch(),
                (a, b, c, d) -> a + "|" + b, Set.of(READ_PODS), FIXED);

        ExecutionResult r = ex.execute(run(AutonomyLevel.BOUNDED_AUTO, 10),
                Plan.of(read(1, READ_PODS)), AlertSeverity.P2);

        assertThat(r.run().status()).isEqualTo(RunStatus.FAILED);
        assertThat(store.finished).hasSize(1);
        AgentStep failed = store.finished.get(0);
        assertThat(failed.status()).isEqualTo(StepStatus.FAILED);
        assertThat(failed.errorMessage()).contains("IllegalStateException").contains("403");
    }

    @Test
    @DisplayName("★ kill switch 优先于放权判定：mode=OFF 时连只读都拦下")
    void killSwitchTakesPrecedence() {
        Fixture f = fixture(AutonomyLevel.BOUNDED_AUTO, Set.of(READ_PODS), RunMode.OFF);
        ExecutionResult r = f.executor.execute(run(AutonomyLevel.BOUNDED_AUTO, 10),
                Plan.of(read(1, READ_PODS)), AlertSeverity.P2);

        assertThat(r.run().status()).isEqualTo(RunStatus.ABORTED);
        assertThat(f.pods.calls).isZero();
        assertThat(r.stoppedReason()).contains("kill switch");
    }

    @Test
    @DisplayName("★ 已被抢走的步骤不重复执行（幂等）")
    void alreadyClaimedStepIsNotReExecuted() {
        Fixture f = fixture(AutonomyLevel.BOUNDED_AUTO, Set.of(READ_PODS), RunMode.FULL);
        f.store.alreadyClaimed = true;
        ExecutionResult r = f.executor.execute(run(AutonomyLevel.BOUNDED_AUTO, 10),
                Plan.of(read(1, READ_PODS)), AlertSeverity.P2);

        assertThat(f.pods.calls).as("抢不到就不该执行，否则重复投递会重复动作").isZero();
        // 计划本身跑完了（这一步视为已由别人完成）
        assertThat(r.completed()).isTrue();
    }

    @Test
    @DisplayName("★ 自动白名单是防御性副本：调用方事后 add 不能偷偷放行工具")
    void autoWhitelistIsDefensivelyCopied() {
        Set<String> mutable = new java.util.HashSet<>();
        mutable.add(READ_PODS);
        MemStepStore store = new MemStepStore();
        Executor ex = new Executor(engine(),
                n -> Optional.of(new StubTool(n, "ok")), store, new KillSwitch(),
                (a, b, c, d) -> a + "|" + b, mutable, FIXED);
        assertThat(ex.autoWhitelist()).containsExactly(READ_PODS);

        mutable.add(WRITE);
        assertThat(ex.autoWhitelist())
                .as("构造后修改原集合不得影响 Executor")
                .containsExactly(READ_PODS);
    }

    @Test
    @DisplayName("ExecutionResult 的自洽约束：SUCCEEDED 不得带停止原因，反之必须有")
    void executionResultIsSelfConsistent() {
        AgentRun done = run(AutonomyLevel.BOUNDED_AUTO, 10).finish(RunStatus.SUCCEEDED, T0);
        AgentRun stopped = run(AutonomyLevel.BOUNDED_AUTO, 10).finish(RunStatus.HANDED_OVER, T0);

        assertThat(new ExecutionResult(done, 2, null).completed()).isTrue();
        try {
            new ExecutionResult(done, 2, "不该有");
            org.assertj.core.api.Assertions.fail("SUCCEEDED 带 stoppedReason 应当被拒");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("矛盾");
        }
        try {
            new ExecutionResult(stopped, 1, null);
            org.assertj.core.api.Assertions.fail("非 SUCCEEDED 缺 stoppedReason 应当被拒");
        } catch (IllegalArgumentException expected) {
            assertThat(expected).hasMessageContaining("必须说清为什么");
        }
    }
}
