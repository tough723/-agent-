package com.oncall.toolgateway;

/**
 * 工具执行审计 —— <b>只追加的事件流</b>。
 *
 * <p><b>幂等职责已经从这里移走</b>，见 {@link ToolExecutionLedger}。
 * 原先这个接口同时管审计和幂等，后果是幂等状态只能存在内存里：
 * 多实例下两个实例各有一份 map，同一个重试请求打到不同实例就会被执行两次，
 * 而二次扩容、二次重启是会出真事故的。
 *
 * <p>两者的生命周期也不同：审计是只追加、保留 180 天、一次调用可以有多条事件；
 * 幂等账本是可变状态、一次调用一行、失败后要能删除以允许重试。
 * 把可变状态塞进只追加的日志表，会让"删掉一行以允许重试"变成篡改审计记录。
 */
public interface ToolAuditLog {

    void recordApproval(String idempotencyKey, Approval approval);

    void recordSuccess(String idempotencyKey, String toolName, String args, String result);

    void recordFailure(String idempotencyKey, String toolName, String args, Throwable error);

    /** 记录参数被夹紧过——安全审计的重点信号，通常意味着模型生成了越界参数。 */
    void recordClamped(String idempotencyKey, String toolName, String rawArgs, String clampedArgs);

    void recordDenied(String toolName, String reason);
}
