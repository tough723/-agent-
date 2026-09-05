package com.oncall.toolgateway;

/**
 * 工具执行幂等账本 —— 不变量 I8 的物理保证。
 *
 * <h2>为什么必须与审计日志分开</h2>
 *
 * 两者是不同的东西，生命周期也不同：
 * <ul>
 *   <li><b>审计日志</b>是只追加的事件流，保留 180 天，一次调用可以有多条
 *       （审批 / 夹紧 / 成功或失败各一条）；</li>
 *   <li><b>幂等账本</b>是可变状态，一次调用只有一行，
 *       且失败后必须能被删除以允许重试。</li>
 * </ul>
 *
 * 给审计表加唯一约束会直接和「一次调用多条事件」冲突；
 * 把可变状态塞进只追加的日志表，则会让「删掉一行以允许重试」这种操作
 * 变成篡改审计记录——那正是审计不能容忍的。
 *
 * <h2>为什么内存实现不够</h2>
 *
 * 单实例下内存版是正确的，多实例下必然失效：两个实例各有一份 map，
 * 同一个重试请求打到不同实例就会被执行两次。
 * <b>二次扩容、二次重启是会出真事故的</b>，所以生产必须用 {@link JdbcToolExecutionLedger}。
 *
 * <h2>为什么是"抢占"而不是"先查后写"</h2>
 *
 * 原先的写法是 {@code if (has(key)) return result; ... recordSuccess(key, result);}。
 * 这在并发下有窗口：两个线程都通过了 {@code has()} 检查，然后都执行。
 * 唯一能真正防止的办法是让<b>抢占本身是一次原子的插入</b>——
 * 主键冲突即表示别人已经抢到，这是数据库能给的保证，应用层给不了。
 */
public interface ToolExecutionLedger {

    /**
     * 抢占执行权。
     *
     * @return {@code true} 表示本次调用获得执行权；
     *         {@code false} 表示该幂等键已被抢占或已完成，<b>本次不得执行</b>
     */
    boolean claim(String idempotencyKey, String toolName);

    /** 是否已有一次<b>完成</b>的执行（可安全重放）。 */
    boolean isCompleted(String idempotencyKey);

    /** 完成结果；未完成（含"已抢占但未结束"）返回 {@code null}。 */
    String resultOf(String idempotencyKey);

    /** 标记完成并写入结果。此后同一幂等键的调用直接返回该结果。 */
    void complete(String idempotencyKey, String result);

    /**
     * 释放执行权（删除该行），允许重试。
     *
     * <p>失败、审批被拒、审批超时都必须调用它，否则这个幂等键会永久停在
     * {@code CLAIMED} 状态，之后所有重试都被判为"重复调用"而拒绝——
     * 表现为"这个操作再也做不了了"，且没有任何报错指向真正的原因。
     */
    void release(String idempotencyKey);
}
