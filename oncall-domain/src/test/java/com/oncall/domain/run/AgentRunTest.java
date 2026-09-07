package com.oncall.domain.run;

import com.oncall.domain.autonomy.AutonomyLevel;
import com.oncall.domain.trace.TraceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentRun} 的构造期不变量。纯领域测试，不碰数据库。
 */
@DisplayName("AgentRun：四项预算护栏与放权快照")
class AgentRunTest {

    private static final Instant T0 = Instant.parse("2026-09-07T03:00:00Z");

    private static AgentRun running() {
        return AgentRun.start("run-1", TraceId.mint(), "grp-1", AutonomyLevel.SHADOW,
                10, 100_000L, new BigDecimal("5.00"), 2, T0);
    }

    @Test
    @DisplayName("start() 产出 RUNNING、游标 0、四项用量归零、未完成")
    void startProducesAFreshRun() {
        AgentRun run = running();
        assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.stepCursor()).isZero();
        assertThat(run.usedSteps()).isZero();
        assertThat(run.usedTokens()).isZero();
        assertThat(run.usedCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(run.finishedAt()).isNull();
        assertThat(run.autonomyLevel()).isEqualTo(AutonomyLevel.SHADOW);
    }

    @Test
    @DisplayName("★ 已用量越过预算即构造失败——护栏不能只是记录")
    void rejectsUsageOverBudget() {
        assertThatThrownBy(() -> new AgentRun("run-1", TraceId.mint(), null, RunStatus.RUNNING,
                AutonomyLevel.SUGGEST, 0, 10, 1000L, new BigDecimal("5.00"), 2,
                11, 0L, BigDecimal.ZERO, 0, T0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越过预算");
        assertThatThrownBy(() -> new AgentRun("run-1", TraceId.mint(), null, RunStatus.RUNNING,
                AutonomyLevel.SUGGEST, 0, 10, 1000L, new BigDecimal("5.00"), 2,
                0, 1001L, BigDecimal.ZERO, 0, T0, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentRun("run-1", TraceId.mint(), null, RunStatus.RUNNING,
                AutonomyLevel.SUGGEST, 0, 10, 1000L, new BigDecimal("5.00"), 2,
                0, 0L, new BigDecimal("5.000001"), 0, T0, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("预算相等不算越界；用 compareTo 而非 equals，因为 5.0 与 5.000000 必须相等")
    void budgetBoundaryIsInclusive() {
        AgentRun atLimit = new AgentRun("run-1", TraceId.mint(), null, RunStatus.RUNNING,
                AutonomyLevel.SUGGEST, 0, 10, 1000L, new BigDecimal("5.00"), 2,
                10, 1000L, new BigDecimal("5.000000"), 0, T0, null);
        assertThat(atLimit.usedCost()).isEqualByComparingTo(atLimit.budgetCost());
        assertThat(atLimit.budgetExhausted()).isTrue();
    }

    @Test
    @DisplayName("预算为 0 是配置错误，不是一次合法的排查")
    void rejectsZeroBudget() {
        assertThatThrownBy(() -> AgentRun.start("run-1", TraceId.mint(), null,
                AutonomyLevel.SHADOW, 0, 1000L, BigDecimal.ONE, 2, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一步都走不了");
    }

    @Test
    @DisplayName("★ finished_at 与终态互为充要")
    void finishedAtMatchesTerminalStatus() {
        assertThatThrownBy(() -> new AgentRun("run-1", TraceId.mint(), null, RunStatus.SUCCEEDED,
                AutonomyLevel.SHADOW, 0, 10, 1000L, BigDecimal.ONE, 2,
                0, 0L, BigDecimal.ZERO, 0, T0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法回答");
        assertThatThrownBy(() -> new AgentRun("run-1", TraceId.mint(), null, RunStatus.RUNNING,
                AutonomyLevel.SHADOW, 0, 10, 1000L, BigDecimal.ONE, 2,
                0, 0L, BigDecimal.ZERO, 0, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能声称已结束");
    }

    @Test
    @DisplayName("★ 放权等级快照不允许为空——否则事后无从判断当时被授权到哪一级")
    void rejectsNullAutonomyLevel() {
        assertThatThrownBy(() -> AgentRun.start("run-1", TraceId.mint(), null,
                null, 10, 1000L, BigDecimal.ONE, 2, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("放权等级快照");
    }

    @Test
    @DisplayName("consume() 让游标与 usedSteps 同步推进——游标落后会导致已付费的步骤被重跑")
    void consumeAdvancesCursorAndUsageTogether() {
        AgentRun after = running().consume(3, 1200L, new BigDecimal("0.42"));
        assertThat(after.stepCursor()).isEqualTo(3);
        assertThat(after.usedSteps()).isEqualTo(3);
        assertThat(after.usedTokens()).isEqualTo(1200L);
        assertThat(after.usedCost()).isEqualByComparingTo(new BigDecimal("0.42"));
        // 快照与预算原样带过去
        assertThat(after.autonomyLevel()).isEqualTo(AutonomyLevel.SHADOW);
        assertThat(after.budgetSteps()).isEqualTo(10);
    }

    @Test
    @DisplayName("consume() 越界即拒绝，而不是先写进去再靠人事后发现")
    void consumeRefusesToExceedBudget() {
        assertThatThrownBy(() -> running().consume(11, 0L, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越过预算");
        assertThatThrownBy(() -> running().consume(-1, 0L, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得为负");
    }

    @Test
    @DisplayName("终态不再推进游标也不再消耗预算")
    void terminalRunRefusesToConsume() {
        AgentRun done = running().finish(RunStatus.HANDED_OVER, T0.plusSeconds(30));
        assertThatThrownBy(() -> done.consume(1, 10L, BigDecimal.ZERO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态");
    }

    @Test
    @DisplayName("finish() 保留快照与预算，且不能二次收尾")
    void finishKeepsSnapshotAndRefusesDoubleFinish() {
        AgentRun done = running().consume(2, 500L, new BigDecimal("0.10"))
                .finish(RunStatus.ABORTED, T0.plusSeconds(60));
        assertThat(done.status()).isEqualTo(RunStatus.ABORTED);
        assertThat(done.finishedAt()).isEqualTo(T0.plusSeconds(60));
        assertThat(done.autonomyLevel()).isEqualTo(AutonomyLevel.SHADOW);
        assertThat(done.usedSteps()).isEqualTo(2);
        assertThatThrownBy(() -> done.finish(RunStatus.SUCCEEDED, T0.plusSeconds(90)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能再次收尾");
        assertThatThrownBy(() -> running().finish(RunStatus.RUNNING, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("需要终态");
    }

    @Test
    @DisplayName("id 超宽必须拒绝而不是截断——截断会让主键指向别的行")
    void rejectsOversizedId() {
        assertThatThrownBy(() -> AgentRun.start("r".repeat(65), TraceId.mint(), null,
                AutonomyLevel.SHADOW, 10, 1000L, BigDecimal.ONE, 2, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("绝不截断");
        assertThatThrownBy(() -> AgentRun.start("  ", TraceId.mint(), null,
                AutonomyLevel.SHADOW, 10, 1000L, BigDecimal.ONE, 2, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("空白");
    }

    @Test
    @DisplayName("budgetExhausted() 在任一项用尽时即为真")
    void budgetExhaustedIsPerDimension() {
        assertThat(running().budgetExhausted()).isFalse();
        assertThat(running().consume(10, 0L, BigDecimal.ZERO).budgetExhausted()).isTrue();
        assertThat(running().consume(0, 100_000L, BigDecimal.ZERO).budgetExhausted()).isTrue();
        assertThat(running().consume(0, 0L, new BigDecimal("5.00")).budgetExhausted()).isTrue();
    }

    @Test
    @DisplayName("RunStatus 只有 RUNNING 是非终态")
    void onlyRunningIsNonTerminal() {
        assertThat(RunStatus.RUNNING.isTerminal()).isFalse();
        assertThat(RunStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(RunStatus.FAILED.isTerminal()).isTrue();
        assertThat(RunStatus.ABORTED.isTerminal()).isTrue();
        assertThat(RunStatus.HANDED_OVER.isTerminal()).isTrue();
        assertThat(RunStatus.values()).hasSize(5);
    }

    // ── 第四个预算：重规划次数（V9 / D3-d） ────────────────────────

    @Test
    @DisplayName("★ 重规划预算可以为 0——与前三项「必须为正」刻意不同")
    void replanBudgetMayBeZero() {
        // 步数/token/成本预算为 0 意味着「一步都走不了」，是配置错误；
        // 而重规划预算为 0 意味着「按最初计划一路走到底」，是合法策略。
        AgentRun noReplan = AgentRun.start("run-1", TraceId.mint(), null,
                AutonomyLevel.SHADOW, 10, 1000L, BigDecimal.ONE, 0, T0);
        assertThat(noReplan.budgetReplans()).isZero();
        assertThat(noReplan.replanBudgetExhausted())
                .as("预算 0 意味着一次都不许重规划")
                .isTrue();
        assertThatThrownBy(noReplan::consumeReplan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终止条件");
    }

    @Test
    @DisplayName("consumeReplan 递增已用次数，且不动步数与游标")
    void consumeReplanAdvancesOnlyTheReplanCounter() {
        AgentRun r = running().consumeReplan();
        assertThat(r.usedReplans()).isEqualTo(1);
        assertThat(r.usedSteps()).as("重规划不消耗步数——它换掉的是剩下要走的步骤").isZero();
        assertThat(r.stepCursor()).as("游标不该因为改主意而推进").isZero();
        assertThat(r.replanBudgetExhausted()).isFalse();
    }

    @Test
    @DisplayName("★ 越过重规划预算即抛，而不是静默截断")
    void consumeReplanRefusesToExceedBudget() {
        AgentRun r = running().consumeReplan().consumeReplan();   // 夹具预算为 2
        assertThat(r.usedReplans()).isEqualTo(2);
        assertThat(r.replanBudgetExhausted()).isTrue();
        assertThatThrownBy(r::consumeReplan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重规划预算已耗尽");
    }

    @Test
    @DisplayName("已收尾的 run 不再重规划")
    void consumeReplanRefusesAfterFinish() {
        AgentRun done = running().finish(RunStatus.SUCCEEDED, T0);
        assertThatThrownBy(done::consumeReplan)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("终态");
    }

    @Test
    @DisplayName("构造期拒绝负数与越界的重规划计数")
    void rejectsNegativeOrOverBudgetReplans() {
        assertThatThrownBy(() -> new AgentRun("run-1", TraceId.mint(), null, RunStatus.RUNNING,
                AutonomyLevel.SHADOW, 0, 10, 1000L, BigDecimal.ONE, -1,
                0, 0L, BigDecimal.ZERO, 0, T0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不得为负");
        assertThatThrownBy(() -> new AgentRun("run-1", TraceId.mint(), null, RunStatus.RUNNING,
                AutonomyLevel.SHADOW, 0, 10, 1000L, BigDecimal.ONE, 2,
                0, 0L, BigDecimal.ZERO, 3, T0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("护栏形同虚设");
    }
}
