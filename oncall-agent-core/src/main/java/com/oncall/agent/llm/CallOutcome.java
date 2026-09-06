package com.oncall.agent.llm;

import java.util.Objects;

/**
 * 一次<b>成功</b>的模型调用实际发生了什么。
 *
 * <p><b>它存在的理由是一张表。</b>{@code db/migration/V4__llm_metering.sql} 里的
 * {@code llm_call_log} 有四列只有 {@link ResilientChatModel} 这一层能填：
 *
 * <table border="1">
 *   <caption>四列的来源</caption>
 *   <tr><th>列</th><th>约束</th><th>为什么外面填不出来</th></tr>
 *   <tr><td>{@code model}</td><td>{@code NOT NULL}</td>
 *       <td>链上究竟是谁服务了这次调用——failover 之后就不是主模型了，
 *           而调用方拿到的 {@code ChatResponse} 上没有任何模型标识</td></tr>
 *   <tr><td>{@code latency_ms}</td><td>{@code NOT NULL}</td>
 *       <td>必须包含重试与退避的全部时间，否则 P95 测的不是用户感知的延迟</td></tr>
 *   <tr><td>{@code is_retry}</td><td>{@code NOT NULL}</td>
 *       <td>重试发生在装饰器内部，外面看不见</td></tr>
 *   <tr><td>{@code failover_from}</td><td>可空</td>
 *       <td>同上；这一列是 DDL 注释里点名的「延迟维度的硬伤」的证据</td></tr>
 * </table>
 *
 * <p>于是任何要写 {@code llm_call_log} 的代码都只能<b>编</b>这四列，
 * 而其中三列是 {@code NOT NULL}。这与轨道 C1 里
 * {@code ToolAuditLog.recordSuccess} 喂不满 {@code tool_audit_log} 必填列
 * 是同一个形状：<b>声明存在，实现缺席。</b>
 *
 * <p>本类只负责把事实交出去，不负责写库——
 * 写库要凑齐 {@code prompt_version} 与 {@code call_type}，
 * 那两列的知识在 {@code agent.prompt} 与编排层，
 * 而 F11 禁止本包依赖它们。
 *
 * @param model                  最终服务这次调用的模型标识（{@code ModelEntry.name()}，不是类名）
 * @param latencyMs              从进入 {@code call()} 到拿到响应的总毫秒数，<b>含重试与退避</b>
 * @param attemptsOnServingModel 在服务方模型上尝试了几次，从 1 开始
 * @param failoverFrom           紧邻的上一个被放弃的模型标识；没有 failover 时为 {@code null}
 */
public record CallOutcome(
        String model,
        long latencyMs,
        int attemptsOnServingModel,
        String failoverFrom) {

    public CallOutcome {
        if (model == null || model.isBlank()) {
            // 空标识写进 llm_call_log.model 会让「哪个模型在挂」这个问题失去答案，
            // 而那一列是 NOT NULL——宁可在这里炸，也不要写一行没有归属的计量数据。
            throw new IllegalArgumentException("model 不能为空");
        }
        if (latencyMs < 0) {
            // 只可能是时钟被换掉了。负延迟写进表里会污染 P95 统计，
            // 而且它是静默的——聚合查询只会给出一个偏小的数，没人会怀疑。
            throw new IllegalArgumentException("latencyMs 不能为负: " + latencyMs);
        }
        if (attemptsOnServingModel < 1) {
            throw new IllegalArgumentException(
                    "attemptsOnServingModel 至少为 1: " + attemptsOnServingModel);
        }
        if (failoverFrom != null && failoverFrom.isBlank()) {
            // 空白串与 null 语义不同：null 表示「没有 failover」，
            // 空白串表示「有 failover 但不知道从谁切过来的」。
            // 后者是数据缺陷，不该被当成正常值存下来。
            throw new IllegalArgumentException(
                    "failoverFrom 不能为空白串——没有 failover 就传 null");
        }
    }

    /**
     * 这次调用是否发生过重试。直接对应 {@code llm_call_log.is_retry}。
     *
     * <p>DDL 注释写得很清楚：重试的 token 成本可忽略（+1.4%），
     * 但延迟 +8s 会击穿 P95。所以这一列<b>不是</b>成本字段，是延迟字段。
     */
    public boolean isRetry() {
        return attemptsOnServingModel > 1;
    }

    /** 是否发生过模型切换。 */
    public boolean failedOver() {
        return failoverFrom != null;
    }

    /**
     * 便捷工厂：没有 failover 的成功调用。
     *
     * @throws NullPointerException 任一参数为 null（{@code failoverFrom} 除外，它本来就允许 null）
     */
    public static CallOutcome firstModelServed(String model, long latencyMs, int attempts) {
        Objects.requireNonNull(model, "model");
        return new CallOutcome(model, latencyMs, attempts, null);
    }
}
