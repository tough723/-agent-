package com.oncall.toolgateway;

/**
 * 工具执行审计。
 *
 * <p>同时承担<b>幂等</b>职责：{@code idempotencyKey} 上的唯一约束是"重试/消息重投不会二次扩容"的物理保证。
 * 原方案完全没有幂等设计，网络重试会导致二次扩容、二次重启——这是会出真事故的。
 */
public interface ToolAuditLog {

    /** 该幂等键是否已执行过。 */
    boolean has(String idempotencyKey);

    /** 取回上次执行结果，用于重放时直接返回，不再真正执行。 */
    String resultOf(String idempotencyKey);

    void recordApproval(String idempotencyKey, Approval approval);

    void recordSuccess(String idempotencyKey, String toolName, String args, String result);

    void recordFailure(String idempotencyKey, String toolName, String args, Throwable error);

    /** 记录参数被夹紧过——安全审计的重点信号，通常意味着模型生成了越界参数。 */
    void recordClamped(String idempotencyKey, String toolName, String rawArgs, String clampedArgs);

    void recordDenied(String toolName, String reason);
}
