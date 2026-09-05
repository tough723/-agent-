package com.oncall.toolgateway.governance;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 内存实现，供单实例 / 本地 / S0 影子模式起步。生产应落库。 */
public final class InMemoryToolPolicyChangeAudit implements ToolPolicyChangeAudit {

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    /** 插入序号：给同一毫秒内的事件定序，见 {@link Entry#seq()}。 */
    private final java.util.concurrent.atomic.AtomicLong seq =
            new java.util.concurrent.atomic.AtomicLong();

    @Override
    public void recordApplied(ToolPolicyChangeTicket ticket, String actor, Outcome outcome,
                              long atMillis) {
        entries.add(new Entry(ticket.change().toolName(), actor, outcome,
                ticket.reason(), ticket.change().describe(), atMillis, seq.incrementAndGet()));
    }

    @Override
    public void recordRejected(ToolPolicyChangeTicket ticket, String actor, String reason,
                               long atMillis) {
        entries.add(new Entry(ticket.change().toolName(), actor, Outcome.REJECTED,
                reason, ticket.change().describe(), atMillis, seq.incrementAndGet()));
    }

    @Override
    public void recordProposed(ToolPolicyChangeTicket ticket) {
        entries.add(new Entry(ticket.change().toolName(), ticket.requester(), Outcome.PROPOSED,
                ticket.reason(), ticket.change().describe(),
                ticket.createdAtMillis(), seq.incrementAndGet()));
    }

    @Override
    public List<Entry> history(String toolName) {
        return entries.stream()
                .filter(e -> e.toolName().equals(toolName))
                .sorted(Entry.order())
                .toList();
    }

    @Override
    public List<Entry> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return entries.stream()
                .sorted(Entry.order().reversed())
                .limit(limit)
                .toList();
    }

    /** 测试辅助。 */
    public int size() {
        return entries.size();
    }
}
