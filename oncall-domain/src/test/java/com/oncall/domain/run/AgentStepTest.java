package com.oncall.domain.run;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link AgentStep} 的构造期不变量。纯领域测试，不碰数据库。
 */
@DisplayName("AgentStep：单步的收尾不变量与列宽")
class AgentStepTest {

    private static final Instant T0 = Instant.parse("2026-09-07T03:00:00Z");

    private static AgentStep running() {
        return AgentStep.start("step-1", "run-1", 0, "scale_replicas",
                "{\"replicas\":3}", "run-1|0|scale_replicas|{}", T0);
    }

    @Test
    @DisplayName("start() 产出 RUNNING、未结束、无结果无错误")
    void startProducesAFreshStep() {
        AgentStep s = running();
        assertThat(s.status()).isEqualTo(StepStatus.RUNNING);
        assertThat(s.finishedAt()).isNull();
        assertThat(s.resultSummary()).isNull();
        assertThat(s.errorMessage()).isNull();
        assertThat(s.idempotencyKey()).isEqualTo("run-1|0|scale_replicas|{}");
    }

    @Test
    @DisplayName("★ finishedAt 与终态互为充要")
    void finishedAtMatchesTerminalStatus() {
        assertThatThrownBy(() -> new AgentStep("s", "r", 0, null, null, "k",
                StepStatus.SUCCEEDED, null, null, T0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法回答");
        assertThatThrownBy(() -> new AgentStep("s", "r", 0, null, null, "k",
                StepStatus.RUNNING, null, null, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能声称已结束");
    }

    @Test
    @DisplayName("★ FAILED 必须带错误信息；成功的一步带错误信息同样是数据缺陷")
    void failedRequiresErrorMessage() {
        assertThatThrownBy(() -> running().fail("   ", T0.plusSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无从下手");
        assertThatThrownBy(() -> new AgentStep("s", "r", 0, null, null, "k",
                StepStatus.SUCCEEDED, "ok", "boom", T0, T0.plusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("数据缺陷");
    }

    @Test
    @DisplayName("★ finishedAt 早于 startedAt 即拒绝——墙上时钟被回拨时会产生负时长")
    void rejectsNegativeDuration() {
        assertThatThrownBy(() -> running().succeed("ok", T0.minusSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("时长为负");
    }

    @Test
    @DisplayName("succeed() / fail() 保留幂等键与开始时刻，且不能二次收尾")
    void finishKeepsWriteOnceColumnsAndRefusesDoubleFinish() {
        AgentStep ok = running().succeed("扩容到 3 副本", T0.plusSeconds(12));
        assertThat(ok.status()).isEqualTo(StepStatus.SUCCEEDED);
        assertThat(ok.resultSummary()).isEqualTo("扩容到 3 副本");
        assertThat(ok.idempotencyKey()).isEqualTo("run-1|0|scale_replicas|{}");
        assertThat(ok.startedAt()).isEqualTo(T0);
        assertThat(ok.finishedAt()).isEqualTo(T0.plusSeconds(12));

        assertThatThrownBy(() -> ok.fail("boom", T0.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不能二次收尾");
        assertThatThrownBy(() -> ok.succeed("again", T0.plusSeconds(20)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("fail() 记下错误信息并清空结果")
    void failRecordsMessage() {
        AgentStep bad = running().fail("K8s API 403", T0.plusSeconds(3));
        assertThat(bad.status()).isEqualTo(StepStatus.FAILED);
        assertThat(bad.errorMessage()).isEqualTo("K8s API 403");
        assertThat(bad.resultSummary()).isNull();
    }

    @Test
    @DisplayName("列宽一律拒绝而不是截断——截断会让主键指向别的行")
    void rejectsOversizedColumns() {
        assertThatThrownBy(() -> AgentStep.start("s".repeat(65), "r", 0, null, null, "k", T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("绝不截断");
        assertThatThrownBy(() -> AgentStep.start("s", "r".repeat(65), 0, null, null, "k", T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("runId");
        assertThatThrownBy(() -> AgentStep.start("s", "r", 0, "t".repeat(192), null, "k", T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("toolName");
        assertThatThrownBy(() -> AgentStep.start("s", "r", 0, null, null, "k".repeat(129), T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> AgentStep.start("s", "r", 0, null, null, "  ", T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("空白");
        assertThatThrownBy(() -> AgentStep.start("s", "r", -1, null, null, "k", T0))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("seq");
    }

    @Test
    @DisplayName("StepStatus 刻意只有三个值——PENDING 与 started_at NOT NULL 矛盾")
    void stepStatusHasExactlyThreeValues() {
        assertThat(StepStatus.values()).hasSize(3);
        assertThat(StepStatus.RUNNING.isTerminal()).isFalse();
        assertThat(StepStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(StepStatus.FAILED.isTerminal()).isTrue();
    }
}
