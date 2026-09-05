package com.oncall.toolgateway;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版审计实现。
 *
 * <p>用途：① 单元测试 ② 本地开发 ③ S0 影子模式的快速起步。
 * <b>生产必须换成 {@code JdbcToolAuditLog}</b>——幂等的物理保证来自
 * {@code agent_step.idempotency_key} 的 UNIQUE 约束，内存实现在多实例下会失效。
 */
public class InMemoryToolAuditLog implements ToolAuditLog {

    /** 已执行记录：key -> 执行结果。 */
    private final Map<String, String> executed = new ConcurrentHashMap<>();
    /** 事件流水，供断言与调试。 */
    private final List<String> events = new CopyOnWriteArrayList<>();

    @Override
    public boolean has(String idempotencyKey) {
        return executed.containsKey(idempotencyKey);
    }

    @Override
    public String resultOf(String idempotencyKey) {
        return executed.get(idempotencyKey);
    }

    @Override
    public void recordApproval(String idempotencyKey, Approval approval) {
        events.add("APPROVAL:" + idempotencyKey + ":" + (approval.approved() ? "approved" : "denied")
                + (approval.expired() ? ":expired" : ""));
    }

    @Override
    public void recordSuccess(String idempotencyKey, String toolName, String args, String result) {
        executed.put(idempotencyKey, result);
        events.add("SUCCESS:" + toolName);
    }

    @Override
    public void recordFailure(String idempotencyKey, String toolName, String args, Throwable error) {
        // 失败不写入 executed —— 允许重试
        events.add("FAILURE:" + toolName + ":" + error.getClass().getSimpleName());
    }

    @Override
    public void recordClamped(String idempotencyKey, String toolName, String rawArgs, String clampedArgs) {
        events.add("CLAMPED:" + toolName);
    }

    @Override
    public void recordDenied(String toolName, String reason) {
        events.add("DENIED:" + toolName + ":" + reason);
    }

    /** 测试辅助：取事件流水。 */
    public List<String> events() {
        return List.copyOf(events);
    }

    /** 测试辅助：某类事件的计数。 */
    public long countOf(String prefix) {
        return events.stream().filter(e -> e.startsWith(prefix)).count();
    }

    public void clear() {
        executed.clear();
        events.clear();
    }
}
