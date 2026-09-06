package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolSource;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条工具审计事件 —— 与 {@code tool_audit_log} 的一行一一对应。
 *
 * <p><b>这个类存在的理由，就是让「写不进去」在编译期与构造期暴露，而不是在数据库里。</b>
 * 表的 7 个 {@code NOT NULL} 列全部是 record 组件，且在紧凑构造器里校验：
 * 少一个字段，代码根本构造不出这个对象。
 * 相比之下，原先那 5 个 {@code recordXxx(String, String, ...)} 方法
 * 可以让实现往必填列塞任何假值，而编译器和调用点都不会有意见。
 *
 * <p><b>参数与结果必须已经脱敏</b>。组件名刻意叫 {@code argsMasked} / {@code resultMasked}
 * 而不是 {@code args} / {@code result}：让「传原文」这件事在调用点看起来就是错的。
 * 脱敏由 {@link ArgMasker} 负责。
 */
public record ToolAuditEvent(
        ToolAuditContext context,
        String toolName,
        ToolSource toolSource,
        RiskLevel riskLevel,
        String argsMasked,
        String resultMasked,
        GateOutcome gateOutcome,
        String deniedReason,
        Integer durationMs,
        Instant calledAt) {

    /** 与 DDL 列宽一致。 */
    public static final int MAX_TOOL_NAME = 191;
    public static final int MAX_REASON = 500;

    public ToolAuditEvent {
        Objects.requireNonNull(context, "context：没有 trace 的审计行无法与 agent_run 关联");
        Objects.requireNonNull(toolSource, "toolSource：审计要能分清 LOCAL 与 MCP");
        Objects.requireNonNull(riskLevel, "riskLevel：审计要能按风险级统计");
        Objects.requireNonNull(gateOutcome, "gateOutcome");
        Objects.requireNonNull(calledAt, "calledAt");

        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        toolName = toolName.trim();
        if (toolName.length() > MAX_TOOL_NAME) {
            throw new IllegalArgumentException("toolName 超过 " + MAX_TOOL_NAME + " 字符：" + toolName.length());
        }

        // args_masked 是 NOT NULL，但允许空串：被策略默认拒绝的调用根本没走到参数这一步，
        // 此时「没有参数」是事实，编一个占位串反而是造假。null 与空串的区别是刻意的：
        // null 表示「这一列不该有值」，空串表示「有这一项，内容是空」。
        argsMasked = argsMasked == null ? "" : argsMasked;

        if (deniedReason != null) {
            deniedReason = deniedReason.isBlank() ? null : deniedReason.trim();
            if (deniedReason != null && deniedReason.length() > MAX_REASON) {
                deniedReason = deniedReason.substring(0, MAX_REASON);
            }
        }
        // 被拦下却没有原因，这一行就没有价值：
        // 排查时第一个问题永远是「为什么被拦」，而这个问题只能由这列回答。
        if ((gateOutcome == GateOutcome.DENIED || gateOutcome == GateOutcome.TIMED_OUT)
                && deniedReason == null) {
            throw new IllegalArgumentException(
                    gateOutcome + " 必须给出 deniedReason：被拦下却不说原因，审计行无法用于排查");
        }
        // 反向也管：放行的事件带上拒绝原因，说明调用点把两件事混了。
        if ((gateOutcome == GateOutcome.PASSED || gateOutcome == GateOutcome.CLAMPED)
                && deniedReason != null) {
            throw new IllegalArgumentException(
                    gateOutcome + " 不应带 deniedReason（放行与拦下不能同时成立）");
        }
        if (durationMs != null && durationMs < 0) {
            throw new IllegalArgumentException("durationMs 不能为负：" + durationMs);
        }
    }

    /** 关卡放行并执行完成。执行本身失败也走这里（见 {@link GateOutcome} 对 FAILED 的说明）。 */
    public static ToolAuditEvent passed(ToolAuditContext context, String toolName, ToolSource source,
                                        RiskLevel risk, String argsMasked, String resultMasked,
                                        Integer durationMs) {
        return new ToolAuditEvent(context, toolName, source, risk, argsMasked, resultMasked,
                GateOutcome.PASSED, null, durationMs, Instant.now());
    }

    /** 被拦下：策略默认拒绝 / kill switch / 审批被拒。 */
    public static ToolAuditEvent denied(ToolAuditContext context, String toolName, ToolSource source,
                                        RiskLevel risk, String argsMasked, String reason) {
        return new ToolAuditEvent(context, toolName, source, risk, argsMasked, null,
                GateOutcome.DENIED, reason, null, Instant.now());
    }

    /**
     * 参数被夹紧。{@code argsMasked} 放<b>原始</b>（越界的）参数，
     * {@code resultMasked} 放<b>夹紧后</b>的参数——两者都要脱敏。
     *
     * <p>复用 {@code result_masked} 列存夹紧结果，是因为这一行的「结果」就是夹紧后的参数；
     * 加一列 {@code clamped_args} 只为这一种事件服务，会让其余四种事件的行多一个恒空列。
     */
    public static ToolAuditEvent clamped(ToolAuditContext context, String toolName, ToolSource source,
                                         RiskLevel risk, String rawArgsMasked, String clampedArgsMasked) {
        return new ToolAuditEvent(context, toolName, source, risk, rawArgsMasked, clampedArgsMasked,
                GateOutcome.CLAMPED, null, null, Instant.now());
    }

    /** 审批超时。 */
    public static ToolAuditEvent timedOut(ToolAuditContext context, String toolName, ToolSource source,
                                          RiskLevel risk, String argsMasked, String reason) {
        return new ToolAuditEvent(context, toolName, source, risk, argsMasked, null,
                GateOutcome.TIMED_OUT, reason, null, Instant.now());
    }

    @Override
    public String toString() {
        // 刻意不打印 argsMasked / resultMasked：toString 会进普通日志，
        // 而普通日志没有审计表的脱敏与保留期约束。
        return "ToolAuditEvent[" + gateOutcome + " " + toolName + " " + riskLevel
                + " " + toolSource + " trace=" + context.traceId() + "]";
    }
}
