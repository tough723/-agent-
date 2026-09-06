package com.oncall.toolgateway;

import com.oncall.domain.trace.TraceId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ToolAuditContext} 的 trace 相关行为。
 *
 * <p>本类守两件事：
 * <ol>
 *   <li><b>校验只有一份。</b>构造器把长度与字符集的判断委托给 {@link TraceId}，
 *       而不是自己再写一遍。两处各存一份规则的话早晚有一份先被改，
 *       而 traceId 的规则是安全控制（防日志注入），规则分叉的代价很高。</li>
 *   <li><b>一次 run 全程同一个 trace，这件事在结构上不可违反。</b>
 *       {@code forStep} / {@code forRun} <b>刻意不接受 traceId 参数</b>——
 *       靠调用方自觉传同一个值，就一定会有某一步传错，
 *       而后果是那次排查的记录从中间断成两段，查的时候看不出断过。</li>
 * </ol>
 */
class ToolAuditContextTraceTest {

    // ------------------------------------------------------------ 校验委托

    @Test
    @DisplayName("构造器拒绝换行 —— 这是防日志注入，不是整洁问题")
    void constructorRejectsNewline() {
        // ToolAuditContext.toString() 会把 traceId 拼进日志行，
        // 所以允许换行就等于允许伪造日志行。
        assertThatThrownBy(() -> ToolAuditContext.of("trace-abc\n[INFO] 操作已审批"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceId")
                .hasMessageContaining("非法字符");
    }

    @Test
    @DisplayName("构造器拒绝超长，且报错里带列宽 64")
    void constructorRejectsTooLong() {
        assertThatThrownBy(() -> ToolAuditContext.of("x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceId")
                .hasMessageContaining("64");
    }

    @Test
    @DisplayName("MAX_TRACE_ID 与 TraceId.MAX_LENGTH 是同一个数，不是两份 64")
    void maxTraceIdHasSingleSource() {
        assertThat(ToolAuditContext.MAX_TRACE_ID).isEqualTo(TraceId.MAX_LENGTH);
        assertThat(TraceId.MAX_LENGTH).isEqualTo(64);
    }

    @Test
    @DisplayName("首尾空白仍被修掉（委托校验没有把原有的归一化弄丢）")
    void constructorStillTrims() {
        assertThat(ToolAuditContext.of("  trace-abc  ").traceId()).isEqualTo("trace-abc");
    }

    // ------------------------------------------------------------ 工厂

    @Test
    @DisplayName("of(TraceId) 把已校验的值原样带进来")
    void ofTraceId() {
        ToolAuditContext c = ToolAuditContext.of(TraceId.adopt("trace-abc"));
        assertThat(c.traceId()).isEqualTo("trace-abc");
        assertThat(c.runId()).isNull();
        assertThat(c.stepId()).isNull();
    }

    @Test
    @DisplayName("of(null TraceId) → 拒绝")
    void ofNullTraceIdRejected() {
        assertThatThrownBy(() -> ToolAuditContext.of((TraceId) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("traceId");
    }

    @Test
    @DisplayName("forStartup 铸出一个合法且可用的上下文")
    void forStartupMintsValidContext() {
        ToolAuditContext c = ToolAuditContext.forStartup();
        assertThat(c.traceId()).startsWith(TraceId.PREFIX);
        assertThat(TraceId.rejectionReason(c.traceId())).isEmpty();
        assertThat(c.runId()).isNull();
        assertThat(c.stepId()).isNull();
    }

    @Test
    @DisplayName("forStartup 每次都不同 —— 所以它只能用于启动期，不能当默认值")
    void forStartupIsNotAConstant() {
        assertThat(ToolAuditContext.forStartup().traceId())
                .isNotEqualTo(ToolAuditContext.forStartup().traceId());
    }

    // ------------------------------------------------------------ 派生

    @Test
    @DisplayName("★ forStep 保持 traceId 与 runId 不变，只换 stepId")
    void forStepPreservesTraceAndRun() {
        ToolAuditContext run = new ToolAuditContext("trace-abc", "run-1", "step-1", "alice");

        ToolAuditContext next = run.forStep("step-2");

        assertThat(next.traceId()).isEqualTo("trace-abc");
        assertThat(next.runId()).isEqualTo("run-1");
        assertThat(next.stepId()).isEqualTo("step-2");
        assertThat(next.operator()).isEqualTo("alice");
        assertThat(run.stepId()).as("原对象不变").isEqualTo("step-1");
    }

    @Test
    @DisplayName("连派生十步，trace 一个都没变 —— trace_id 的唯一用途就是串起一次排查")
    void traceSurvivesManySteps() {
        ToolAuditContext c = ToolAuditContext.of(TraceId.adopt("trace-abc")).forRun("run-1");
        for (int i = 1; i <= 10; i++) {
            c = c.forStep("step-" + i);
            assertThat(c.traceId()).as("第 %d 步", i).isEqualTo("trace-abc");
            assertThat(c.stepId()).isEqualTo("step-" + i);
        }
    }

    @Test
    @DisplayName("forRun 保持 traceId，并清掉 stepId（新的 run 还没有步）")
    void forRunPreservesTraceAndClearsStep() {
        ToolAuditContext step = new ToolAuditContext("trace-abc", "run-1", "step-9", "alice");

        ToolAuditContext newRun = step.forRun("run-2");

        assertThat(newRun.traceId()).isEqualTo("trace-abc");
        assertThat(newRun.runId()).isEqualTo("run-2");
        assertThat(newRun.stepId()).as("新 run 不该继承上一个 run 的步").isNull();
        assertThat(newRun.operator()).isEqualTo("alice");
    }

    @Test
    @DisplayName("派生出的上下文仍能通过全部校验")
    void derivedContextsStayValid() {
        ToolAuditContext c = ToolAuditContext.forStartup().forRun("run-1").forStep("step-1");
        assertThat(TraceId.rejectionReason(c.traceId())).isEmpty();
        assertThat(c.toString()).contains("traceId=");
    }
}
