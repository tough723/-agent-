package com.oncall.domain.run;

import java.time.Instant;
import java.util.Objects;

/**
 * 排查的单步 —— {@code agent_step} 表的一行。11 个组件与 V2 的 11 列一一对应。
 *
 * <h2>★ {@code idempotencyKey} 是幂等的<b>物理</b>保证</h2>
 * <p>V2 的列注释原文：「幂等的物理保证；多实例下应用层幂等会失效」。
 * 应用层的 {@code IdempotencyStore} 是内存态：两个 worker 同时拿到同一个
 * 重投消息，各自查内存都说「没执行过」，于是都执行。
 * {@code uq_agent_step_idem} 的 UNIQUE 约束是唯一兜底 ——
 * 但要注意它是<b>探测器不是阻止器</b>：约束冲突发生在插入时，
 * 所以「抢占」必须体现为这次插入本身，而不是「先查后写」。
 *
 * <h2>构造期不变量</h2>
 * <ul>
 *   <li><b>{@code finishedAt} 与终态互为充要</b> —— 与 {@link AgentRun} 同一条规则。
 *       单步同样要能回答「这一步什么时候结束的」。</li>
 *   <li><b>{@code FAILED} 必须带 {@code errorMessage}</b> ——
 *       一步失败了却没有任何错误信息，排查时等于什么都没有。
 *       反过来，成功的步骤带错误信息也是数据缺陷。</li>
 *   <li><b>{@code finishedAt} 不得早于 {@code startedAt}</b> ——
 *       墙上时钟被 NTP 回拨时会出现负时长；这里拒绝而不是记下负数。</li>
 *   <li><b>列宽一律拒绝而不是截断</b> —— 截断后的主键会指向另一行。</li>
 * </ul>
 */
public record AgentStep(
        String id,
        String runId,
        int seq,
        String toolName,
        String argsJson,
        String idempotencyKey,
        StepStatus status,
        String resultSummary,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt) {

    /** 列宽：{@code id} / {@code run_id} 是 VARCHAR(64)。 */
    public static final int MAX_ID_LENGTH = 64;
    /** 列宽：{@code tool_name} 是 VARCHAR(191)。 */
    public static final int MAX_TOOL_NAME_LENGTH = 191;
    /** 列宽：{@code idempotency_key} 是 VARCHAR(128)。 */
    public static final int MAX_IDEM_KEY_LENGTH = 128;

    public AgentStep {
        requireFits(id, "id", MAX_ID_LENGTH);
        requireFits(runId, "runId", MAX_ID_LENGTH);
        requireFits(idempotencyKey, "idempotencyKey", MAX_IDEM_KEY_LENGTH);
        if (toolName != null) {
            requireFits(toolName, "toolName", MAX_TOOL_NAME_LENGTH);
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        if (seq < 0) {
            throw new IllegalArgumentException("seq 不得为负：" + seq);
        }
        // ★ finishedAt 与终态互为充要。
        if (status.isTerminal() && finishedAt == null) {
            throw new IllegalArgumentException("status=" + status
                    + " 是终态，finishedAt 不得为空——否则无法回答「这一步什么时候结束的」");
        }
        if (!status.isTerminal() && finishedAt != null) {
            throw new IllegalArgumentException("status=RUNNING 却带了 finishedAt="
                    + finishedAt + "——还在跑的一步不能声称已结束");
        }
        // ★ FAILED 必须带错误信息；成功的一步带错误信息同样是数据缺陷。
        if (status == StepStatus.FAILED && isBlank(errorMessage)) {
            throw new IllegalArgumentException(
                    "status=FAILED 但 errorMessage 为空——一步失败了却没有任何错误信息，排查时无从下手");
        }
        if (status != StepStatus.FAILED && !isBlank(errorMessage)) {
            throw new IllegalArgumentException("status=" + status
                    + " 却带了 errorMessage——成功的一步不该有错误信息，这是数据缺陷");
        }
        if (finishedAt != null && finishedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("finishedAt=" + finishedAt
                    + " 早于 startedAt=" + startedAt
                    + "——时长为负。墙上时钟被回拨时就会出现，拒绝而不是记下负数");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static void requireFits(String value, String field, int max) {
        Objects.requireNonNull(value, field + "：不得为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "：不得为空白");
        }
        // 绝不截断：截断后的主键会指向另一行，或让两个不同的步骤撞成同一个 id。
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " 超过列宽 " + max
                    + "（实际 " + value.length() + "）——绝不截断，截断会让主键指向别的行");
        }
    }

    /** 开一步：状态 RUNNING，未结束。 */
    public static AgentStep start(String id, String runId, int seq, String toolName,
                                  String argsJson, String idempotencyKey, Instant startedAt) {
        return new AgentStep(id, runId, seq, toolName, argsJson, idempotencyKey,
                StepStatus.RUNNING, null, null, startedAt, null);
    }

    /**
     * 成功收尾。{@code idempotencyKey} 与 {@code startedAt} 原样带过去——
     * 它们是写入一次的列，收尾不该动它们。
     */
    public AgentStep succeed(String resultSummary, Instant at) {
        requireNotTerminal();
        return new AgentStep(id, runId, seq, toolName, argsJson, idempotencyKey,
                StepStatus.SUCCEEDED, resultSummary, null, startedAt, at);
    }

    /** 失败收尾。{@code message} 不得为空——见构造期不变量。 */
    public AgentStep fail(String message, Instant at) {
        requireNotTerminal();
        return new AgentStep(id, runId, seq, toolName, argsJson, idempotencyKey,
                StepStatus.FAILED, null, message, startedAt, at);
    }

    private void requireNotTerminal() {
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "已经是 " + status + "，单步不能二次收尾——那会让「这一步做过几次」失去唯一答案");
        }
    }
}
