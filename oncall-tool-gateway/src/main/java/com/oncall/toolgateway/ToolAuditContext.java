package com.oncall.toolgateway;

import java.util.Objects;

/**
 * 审计上下文 —— 一次工具调用中**恒定不变、且实现方无权猜测**的那部分事实。
 *
 * <p><b>为什么要有这个类</b>：{@code tool_audit_log} 有 7 个 {@code NOT NULL} 列，
 * 而原先 {@code ToolAuditLog} 的方法签名一个都给不出来——
 * {@code recordSuccess(idempotencyKey, toolName, args, result)} 里既没有 trace，
 * 也分不清 LOCAL 与 MCP，也没有风险级。
 * 当时的选择只有两个：往必填列塞假值，或者不写这个实现。
 * <b>一张字段造假的审计表比没有审计更糟</b>——它会让人以为「查得到」，
 * 而查出来的 trace 全是同一个占位串。所以这个实现被搁置了，直到上下文能显式传进来。
 *
 * <p>本类承载的是「谁、在哪条链路里」；工具本身的事实（名字/来源/风险级）
 * 与每次调用的结论见 {@link ToolAuditEvent}。
 *
 * @param traceId  链路 ID，对应 {@code tool_audit_log.trace_id}（{@code NOT NULL}，≤64）。
 *                 同一个 {@code agent_run} 全程共用一个，是跨表关联排查的主键。
 *                 <b>不允许空</b>：没有 trace 的审计行无法与 {@code agent_run} 关联，
 *                 也就无法回答「这次扩容是哪次排查触发的」——而那正是审计的主要用途。
 * @param runId    运行 ID（可空；本地调试或非 Agent 触发的调用没有 run）
 * @param stepId   步 ID（可空）
 * @param operator 触发人（可空；Agent 自主执行时为 null，人工触发时填工号）
 */
public record ToolAuditContext(String traceId, String runId, String stepId, String operator) {

    /** 与 DDL 的列宽一致：{@code trace_id VARCHAR(64)}。 */
    public static final int MAX_TRACE_ID = 64;
    /** {@code run_id} / {@code step_id} / {@code operator} 同为 {@code VARCHAR(64)}。 */
    public static final int MAX_ID = 64;

    public ToolAuditContext {
        // 在构造时就拒绝，而不是等到 INSERT 撞 NOT NULL 约束。
        // 后者的失败点在数据库里，而调用栈早就走远了——审计写不进去的时候，
        // 你看到的是 SQLException，不是「有人没传 traceId」。
        traceId = requireNonBlank(traceId, "traceId", MAX_TRACE_ID);
        runId = optional(runId, "runId", MAX_ID);
        stepId = optional(stepId, "stepId", MAX_ID);
        operator = optional(operator, "operator", MAX_ID);
    }

    /** 只有 traceId 的最小上下文。 */
    public static ToolAuditContext of(String traceId) {
        return new ToolAuditContext(traceId, null, null, null);
    }

    private static String requireNonBlank(String v, String field, int max) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空：审计行没有 trace 就无法与 agent_run 关联");
        }
        String t = v.trim();
        if (t.length() > max) {
            throw new IllegalArgumentException(field + " 超过 " + max + " 字符：" + t.length());
        }
        return t;
    }

    private static String optional(String v, String field, int max) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        if (t.isEmpty()) {
            return null;      // 空串一律当 null：两种「没有值」在审计里必须只有一种表示
        }
        if (t.length() > max) {
            throw new IllegalArgumentException(field + " 超过 " + max + " 字符：" + t.length());
        }
        return t;
    }

    @Override
    public String toString() {
        // 刻意不打印 operator：toString 会进日志，而日志不是审计表，
        // 没有脱敏与保留期约束。
        return "ToolAuditContext[traceId=" + Objects.toString(traceId, "-") + "]";
    }
}
