package com.oncall.toolgateway;

import com.oncall.domain.trace.TraceId;

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

    /**
     * 与 DDL 的列宽一致：{@code trace_id VARCHAR(64)}。
     *
     * <p><b>刻意引用 {@link TraceId#MAX_LENGTH} 而不是再写一个 64</b>：
     * 两处各存一份的话，改列宽时只会改到其中一处，
     * 而症状是「能构造出对象但 INSERT 失败」——失败点在数据库里，很难往回找。
     */
    public static final int MAX_TRACE_ID = TraceId.MAX_LENGTH;
    /** {@code run_id} / {@code step_id} / {@code operator} 同为 {@code VARCHAR(64)}。 */
    public static final int MAX_ID = 64;

    public ToolAuditContext {
        // 在构造时就拒绝，而不是等到 INSERT 撞 NOT NULL 约束。
        // 后者的失败点在数据库里，而调用栈早就走远了——审计写不进去的时候，
        // 你看到的是 SQLException，不是「有人没传 traceId」。
        // 校验委托给 TraceId，而不是在这里再写一遍长度与字符集规则。
        // 两处各写一份的话，早晚有一份先被改——而 traceId 的规则是安全控制
        // （防日志注入），不是风格约定，规则分叉的代价很高。
        String reason = TraceId.rejectionReason(traceId).orElse(null);
        if (reason != null) {
            throw new IllegalArgumentException("traceId 不可用：" + reason
                    + "——审计行没有合法 trace 就无法与 agent_run 关联");
        }
        traceId = traceId.trim();
        runId = optional(runId, "runId", MAX_ID);
        stepId = optional(stepId, "stepId", MAX_ID);
        operator = optional(operator, "operator", MAX_ID);
    }

    /** 只有 traceId 的最小上下文。 */
    public static ToolAuditContext of(String traceId) {
        return new ToolAuditContext(traceId, null, null, null);
    }

    /**
     * 用已铸好的 {@link TraceId} 建上下文。<b>这是首选入口</b>——
     * 类型本身就说明这个 trace 经过了校验，而 {@code String} 重载说明不了。
     */
    public static ToolAuditContext of(TraceId traceId) {
        if (traceId == null) {
            throw new IllegalArgumentException("traceId 不能为 null");
        }
        return new ToolAuditContext(traceId.value(), null, null, null);
    }

    /**
     * 启动期上下文：铸一个新 trace。
     *
     * <p>用在<b>确实没有上游、也没有 run</b> 的场合——典型是
     * {@code McpToolRegistrar} 的工具纳管：它发生在任何 run 之前，
     * 借用某个 run 的 trace 会把「启动时这个 server 挂了」
     * 记到一次毫不相干的排查名下。
     *
     * <p><b>不要拿它当「懒得传 trace」的默认值</b>：
     * 每次调用都铸一个新的，等于每行审计各自一条 trace，
     * 而 {@code trace_id} 唯一的用途就是把一次排查的所有操作串起来。
     */
    public static ToolAuditContext forStartup() {
        return of(TraceId.mint());
    }

    /**
     * 派生出「同一个 run 的下一步」。
     *
     * <p><b>刻意不给 traceId 参数</b>：一次 {@code agent_run} 全程共用一个 trace，
     * 这条规则如果靠调用方自觉传同一个值，就一定会在某一步被传错，
     * 而后果是那次排查的记录从中间断成两段——查的时候看不出断过。
     * 不给参数，就在结构上没法传错。
     */
    public ToolAuditContext forStep(String newStepId) {
        return new ToolAuditContext(traceId, runId, newStepId, operator);
    }

    /** 派生出「带着 run 的上下文」，同样不接受 traceId。 */
    public ToolAuditContext forRun(String newRunId) {
        return new ToolAuditContext(traceId, newRunId, null, operator);
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
