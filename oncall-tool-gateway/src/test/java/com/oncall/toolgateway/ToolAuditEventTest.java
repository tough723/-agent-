package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 审计事件的构造期校验。
 *
 * <p><b>这一组测试守的是「填不满的列在 JVM 里就报错，而不是在数据库里」。</b>
 * 原先 {@code ToolAuditLog} 的 5 个 {@code recordXxx} 方法可以让实现往
 * {@code NOT NULL} 列塞任何值，编译器和调用点都不会有意见，
 * 于是 {@code JdbcToolAuditLog} 只能选择造假或者不写。
 * 现在必填项是 record 组件，少一个就构造不出对象——
 * 下面每一条断言都在验这件事。
 */
class ToolAuditEventTest {

    private static final ToolAuditContext CTX =
            new ToolAuditContext("trace-1", "run-1", "step-1", "alice");

    private static ToolAuditEvent valid() {
        return ToolAuditEvent.passed(CTX, "scale_replicas", ToolSource.LOCAL,
                RiskLevel.HIGH, "{\"replicas\":2}", "ok", 12);
    }

    // ---------------------------------------------------------- 必填项

    @Test
    @DisplayName("必填项齐备时可以构造，且各字段原样保留")
    void validEventIsAccepted() {
        ToolAuditEvent e = valid();
        assertThat(e.context()).isSameAs(CTX);
        assertThat(e.toolName()).isEqualTo("scale_replicas");
        assertThat(e.toolSource()).isEqualTo(ToolSource.LOCAL);
        assertThat(e.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(e.gateOutcome()).isEqualTo(GateOutcome.PASSED);
        assertThat(e.durationMs()).isEqualTo(12);
        assertThat(e.calledAt()).isNotNull();
        assertThat(e.deniedReason()).as("放行的事件不该有拒绝原因").isNull();
    }

    @Test
    @DisplayName("★ 五个必填组件缺任何一个都当场拒绝")
    void everyMandatoryComponentIsEnforced() {
        assertThatThrownBy(() -> new ToolAuditEvent(null, "t", ToolSource.LOCAL, RiskLevel.HIGH,
                "", null, GateOutcome.PASSED, null, null, Instant.now()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("context");
        assertThatThrownBy(() -> new ToolAuditEvent(CTX, "t", null, RiskLevel.HIGH,
                "", null, GateOutcome.PASSED, null, null, Instant.now()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("toolSource");
        assertThatThrownBy(() -> new ToolAuditEvent(CTX, "t", ToolSource.LOCAL, null,
                "", null, GateOutcome.PASSED, null, null, Instant.now()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("riskLevel");
        assertThatThrownBy(() -> new ToolAuditEvent(CTX, "t", ToolSource.LOCAL, RiskLevel.HIGH,
                "", null, null, null, null, Instant.now()))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("gateOutcome");
        assertThatThrownBy(() -> new ToolAuditEvent(CTX, "t", ToolSource.LOCAL, RiskLevel.HIGH,
                "", null, GateOutcome.PASSED, null, null, null))
                .isInstanceOf(NullPointerException.class).hasMessageContaining("calledAt");
    }

    @Test
    @DisplayName("工具名空白或超长都拒绝——列宽是 VARCHAR(191)")
    void toolNameIsConstrained() {
        assertThatThrownBy(() -> ToolAuditEvent.passed(CTX, "  ", ToolSource.LOCAL,
                RiskLevel.HIGH, "", null, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("toolName");
        assertThatThrownBy(() -> ToolAuditEvent.passed(CTX, "x".repeat(192), ToolSource.LOCAL,
                RiskLevel.HIGH, "", null, 1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("191");
    }

    // ---------------------------------------------------------- 关卡结论的一致性

    @Test
    @DisplayName("★ DENIED / TIMED_OUT 必须带原因——被拦下却不说原因，这一行没法用于排查")
    void denialWithoutReasonIsRejected() {
        assertThatThrownBy(() -> ToolAuditEvent.denied(CTX, "t", ToolSource.LOCAL,
                RiskLevel.HIGH, "", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deniedReason");
        assertThatThrownBy(() -> ToolAuditEvent.denied(CTX, "t", ToolSource.LOCAL,
                RiskLevel.HIGH, "", "   "))
                .as("全空白等于没有原因")
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deniedReason");
        assertThatThrownBy(() -> ToolAuditEvent.timedOut(CTX, "t", ToolSource.LOCAL,
                RiskLevel.HIGH, "", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deniedReason");
    }

    @Test
    @DisplayName("反向也管：放行的事件带上拒绝原因说明调用点把两件事混了")
    void passWithReasonIsRejected() {
        assertThatThrownBy(() -> new ToolAuditEvent(CTX, "t", ToolSource.LOCAL, RiskLevel.HIGH,
                "", null, GateOutcome.PASSED, "被拒了", null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deniedReason");
        assertThatThrownBy(() -> new ToolAuditEvent(CTX, "t", ToolSource.LOCAL, RiskLevel.HIGH,
                "", null, GateOutcome.CLAMPED, "被拒了", null, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("deniedReason");
    }

    @Test
    @DisplayName("args_masked 为 null 时落成空串——列是 NOT NULL，而「没有参数」是事实不是造假")
    void nullArgsBecomeEmptyString() {
        assertThat(ToolAuditEvent.denied(CTX, "t", ToolSource.MCP, RiskLevel.HIGH, null, "拒了")
                .argsMasked()).isEmpty();
    }

    @Test
    @DisplayName("拒绝原因超过 500 字符截断，负耗时拒绝")
    void reasonIsCappedAndNegativeDurationRejected() {
        assertThat(ToolAuditEvent.denied(CTX, "t", ToolSource.LOCAL, RiskLevel.HIGH, "",
                "r".repeat(600)).deniedReason()).hasSize(500);
        assertThatThrownBy(() -> ToolAuditEvent.passed(CTX, "t", ToolSource.LOCAL,
                RiskLevel.HIGH, "", null, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("durationMs");
    }

    // ---------------------------------------------------------- 四个工厂

    @Test
    @DisplayName("四个工厂产出与 DDL 注释逐字一致的四种关卡结论")
    void factoriesProduceTheFourDocumentedOutcomes() {
        assertThat(ToolAuditEvent.passed(CTX, "t", ToolSource.LOCAL, RiskLevel.LOW, "", "r", 1)
                .gateOutcome()).isEqualTo(GateOutcome.PASSED);
        assertThat(ToolAuditEvent.denied(CTX, "t", ToolSource.LOCAL, RiskLevel.LOW, "", "r")
                .gateOutcome()).isEqualTo(GateOutcome.DENIED);
        assertThat(ToolAuditEvent.clamped(CTX, "t", ToolSource.LOCAL, RiskLevel.LOW, "raw", "new")
                .gateOutcome()).isEqualTo(GateOutcome.CLAMPED);
        assertThat(ToolAuditEvent.timedOut(CTX, "t", ToolSource.LOCAL, RiskLevel.LOW, "", "超时")
                .gateOutcome()).isEqualTo(GateOutcome.TIMED_OUT);
        // 闭集只有这四个值，多一个都意味着 DDL 注释要跟着改
        assertThat(GateOutcome.values()).hasSize(4);
    }

    @Test
    @DisplayName("夹紧事件：args 放越界原值、result 放夹紧后的值")
    void clampedEventCarriesBothSides() {
        ToolAuditEvent e = ToolAuditEvent.clamped(CTX, "scale_replicas", ToolSource.LOCAL,
                RiskLevel.HIGH, "{\"replicas\":0}", "{\"replicas\":2}");
        assertThat(e.argsMasked()).isEqualTo("{\"replicas\":0}");
        assertThat(e.resultMasked()).isEqualTo("{\"replicas\":2}");
    }

    @Test
    @DisplayName("toString 不含参数与结果——它会进普通日志，而普通日志没有脱敏与保留期约束")
    void toStringOmitsPayload() {
        ToolAuditEvent e = ToolAuditEvent.passed(CTX, "scale_replicas", ToolSource.LOCAL,
                RiskLevel.HIGH, "{\"service\":\"order\"}", "cpu=95%", 3);
        assertThat(e.toString()).doesNotContain("order").doesNotContain("cpu=95%");
        assertThat(e.toString()).contains("PASSED").contains("trace-1");
    }

    // ---------------------------------------------------------- 上下文

    @Test
    @DisplayName("★ traceId 空白即拒绝——它是 NOT NULL，而占位 trace 会让整张审计表失去关联能力")
    void contextRequiresTraceId() {
        assertThatThrownBy(() -> ToolAuditContext.of((String) null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("traceId");
        assertThatThrownBy(() -> ToolAuditContext.of("   "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("traceId");
        assertThatThrownBy(() -> ToolAuditContext.of("x".repeat(65)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("64");
    }

    @Test
    @DisplayName("上下文的可空字段：空串一律归一成 null，两种「没有值」只留一种表示")
    void contextNormalizesBlankToNull() {
        ToolAuditContext c = new ToolAuditContext(" trace-2 ", "", "  ", null);
        assertThat(c.traceId()).as("首尾空白要修掉，否则同一个 trace 会分裂成两个").isEqualTo("trace-2");
        assertThat(c.runId()).isNull();
        assertThat(c.stepId()).isNull();
        assertThat(c.operator()).isNull();
    }

    @Test
    @DisplayName("上下文的 toString 不含 operator——日志不是审计表")
    void contextToStringOmitsOperator() {
        assertThat(new ToolAuditContext("t", "r", "s", "alice").toString())
                .doesNotContain("alice").contains("t");
    }
}
