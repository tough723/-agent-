package com.oncall.toolgateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现。<b>仅供单实例、本地开发与测试使用。</b>
 *
 * <p>多实例部署必须换 {@link JdbcToolExecutionLedger}：
 * 两个实例各有一份 map，同一个重试请求打到不同实例就会被执行两次。
 *
 * <p>{@link #claim} 用 {@code putIfAbsent} 而不是"先 get 再 put"——
 * 后者在并发下有窗口，两个线程都会认为自己抢到了。
 * 这一条与 JDBC 实现靠主键冲突是同一个道理：<b>抢占必须是原子的</b>。
 */
public final class InMemoryToolExecutionLedger implements ToolExecutionLedger {

    private static final String CLAIMED = "CLAIMED";
    private static final String COMPLETED = "COMPLETED";

    private final Map<String, String[]> entries = new ConcurrentHashMap<>();

    @Override
    public boolean claim(String idempotencyKey, String toolName) {
        return entries.putIfAbsent(idempotencyKey, new String[]{CLAIMED, null}) == null;
    }

    @Override
    public boolean isCompleted(String idempotencyKey) {
        String[] e = entries.get(idempotencyKey);
        return e != null && COMPLETED.equals(e[0]);
    }

    @Override
    public String resultOf(String idempotencyKey) {
        String[] e = entries.get(idempotencyKey);
        // 只有 COMPLETED 才算有结果：已抢占但未结束的行必须返回 null，
        // 否则调用方会把"还没有结果"误读成"结果是 null"，进而把一次并发调用
        // 当成可重放的既有成功。
        return (e != null && COMPLETED.equals(e[0])) ? e[1] : null;
    }

    @Override
    public void complete(String idempotencyKey, String result) {
        // compute 而不是 put：并发下不能把别人已经写好的 COMPLETED 覆盖回 CLAIMED
        entries.compute(idempotencyKey, (k, old) -> new String[]{COMPLETED, result});
    }

    @Override
    public void release(String idempotencyKey) {
        // 必须带状态条件，与 JDBC 版的 "AND state='CLAIMED'" 语义一致。
        // 少了它，一次迟到的失败回调会删掉已经 COMPLETED 的行，
        // 成功的结果就此丢失 —— 下一次重试就真的会再执行一遍。
        entries.computeIfPresent(idempotencyKey,
                (k, e) -> CLAIMED.equals(e[0]) ? null : e);
    }

    /** 测试辅助：当前账本行数。 */
    public int size() {
        return entries.size();
    }
}
