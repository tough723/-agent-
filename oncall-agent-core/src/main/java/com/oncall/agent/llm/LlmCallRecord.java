package com.oncall.agent.llm;

import java.time.Instant;
import java.util.Objects;

/**
 * {@code llm_call_log} 的一行。
 *
 * <p><b>它存在的理由</b>：轨道 C7 的 {@link CallOutcome} 只交出
 * 「哪一层才知道」的四项事实（model / latency_ms / is_retry / failover_from），
 * 而这张表有 17 列，剩下的 {@code prompt_version} 与 {@code call_type}
 * 的知识在 {@code agent.prompt} 与编排层——F11 禁止本包依赖它们。
 * 所以本类是<b>两侧汇合的地方</b>：由上层把 {@code CallOutcome} 与
 * 它自己那部分知识拼成完整一行，再交给 {@link LlmCallLog} 落库。
 *
 * <p><b>为什么在构造期就校验，而不是等数据库报错</b>：
 * {@code trace_id} / {@code call_type} / {@code model} / {@code prompt_version}
 * 都是 {@code NOT NULL}。让空值走到数据库，得到的是一个
 * 与业务无关的 SQL 异常；在这里拒绝，报错才指得向真正的缺陷。
 *
 * @param traceId          必填。把一次排查的所有调用串起来的唯一手段
 * @param runId            可空：启动期的调用（例如工具纳管）没有 run
 * @param callType         PLANNER / EXECUTOR / REPLANNER / REPORTER / CHAT / EMBEDDING
 * @param model            最终服务这次调用的模型；failover 之后不是主模型
 * @param promptVersion    缺了它就无法归因「改 prompt 之后质量变了」
 * @param promptMasked     脱敏后的内容；全量留痕会绕过脱敏管线
 * @param responseMasked   同上
 * @param promptTokens     输入 token
 * @param completionTokens 输出 token
 * @param totalTokens      合计
 * @param cachedTokens     命中缓存的部分，单价约为未命中的 1/30
 * @param latencyMs        <b>含重试与退避</b>，否则 P95 测的不是用户感知的延迟
 * @param retry            重试的 token 成本可忽略，但延迟会击穿 P95——这是延迟字段
 * @param failoverFrom     紧邻的上一个被放弃的模型；没有 failover 时为 null
 * @param status           SUCCESS / FAILED / TIMED_OUT
 * @param calledAt         分区键，不可为空
 */
public record LlmCallRecord(
        String traceId,
        String runId,
        String callType,
        String model,
        String promptVersion,
        String promptMasked,
        String responseMasked,
        int promptTokens,
        int completionTokens,
        int totalTokens,
        int cachedTokens,
        long latencyMs,
        boolean retry,
        String failoverFrom,
        String status,
        Instant calledAt) {

    public LlmCallRecord {
        requireText(traceId, "traceId");
        requireText(callType, "callType");
        requireText(model, "model");
        requireText(promptVersion, "promptVersion");
        requireText(status, "status");
        Objects.requireNonNull(calledAt, "calledAt 不能为 null（它是分区键）");
        for (int[] pair : new int[][] {{promptTokens, 0}, {completionTokens, 1},
                {totalTokens, 2}, {cachedTokens, 3}}) {
            if (pair[0] < 0) {
                throw new IllegalArgumentException(
                        "token 数不能为负：索引 " + pair[1] + " = " + pair[0]);
            }
        }
        if (latencyMs < 0) {
            throw new IllegalArgumentException("latencyMs 不能为负: " + latencyMs);
        }
        if (failoverFrom != null && failoverFrom.isBlank()) {
            // 与 CallOutcome 同一条理由：null 表示「没有 failover」，
            // 空白串表示「有但不知道从谁切过来」——后者是数据缺陷。
            throw new IllegalArgumentException(
                    "failoverFrom 不能为空白串——没有 failover 就传 null");
        }
    }

    private static void requireText(String v, String name) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(name + " 不能为空（该列 NOT NULL）");
        }
    }

    /**
     * 由 C7 的 {@link CallOutcome} 加上调用方那部分知识拼出一行。
     *
     * <p><b>这个工厂是防「四列靠编」的关键</b>：model / latency / retry / failoverFrom
     * 只能从 {@code CallOutcome} 来，调用方没有机会自己填——
     * 而那四列正是从装饰器外面结构上填不出来的。
     */
    public static LlmCallRecord of(CallOutcome outcome,
                                   String traceId,
                                   String runId,
                                   String callType,
                                   String promptVersion,
                                   String promptMasked,
                                   String responseMasked,
                                   int promptTokens,
                                   int completionTokens,
                                   int totalTokens,
                                   int cachedTokens,
                                   String status,
                                   Instant calledAt) {
        Objects.requireNonNull(outcome, "outcome");
        return new LlmCallRecord(traceId, runId, callType,
                outcome.model(), promptVersion, promptMasked, responseMasked,
                promptTokens, completionTokens, totalTokens, cachedTokens,
                outcome.latencyMs(), outcome.isRetry(), outcome.failoverFrom(),
                status, calledAt);
    }
}
