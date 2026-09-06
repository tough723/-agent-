package com.oncall.toolgateway;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存版审计实现。
 *
 * <p>用途：① 单元测试 ② 本地开发 ③ S0 影子模式的快速起步。
 * <b>生产必须换成 {@link JdbcToolAuditLog}</b>——内存实现进程一重启就没了，
 * 而审计的保留期是 180 天，两者根本不是一回事。
 *
 * <p>注意这与幂等是两码事：幂等的物理保证来自 V7 的
 * {@code tool_execution_claim} 主键（见 {@link JdbcToolExecutionLedger}），
 * 与本类无关。
 */
public class InMemoryToolAuditLog implements ToolAuditLog {

    /** 事件流水，供断言与调试。 */
    private final List<ToolAuditEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void record(ToolAuditEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event 不能为 null");
        }
        events.add(event);
    }

    /** 测试辅助：取事件流水（按写入顺序）。 */
    public List<ToolAuditEvent> events() {
        return List.copyOf(events);
    }

    /** 测试辅助：某类关卡结论的计数。 */
    public long countOf(GateOutcome outcome) {
        return events.stream().filter(e -> e.gateOutcome() == outcome).count();
    }

    /** 测试辅助：某个工具名的事件。 */
    public List<ToolAuditEvent> ofTool(String toolName) {
        return events.stream().filter(e -> e.toolName().equals(toolName)).toList();
    }

    public void clear() {
        events.clear();
    }
}
