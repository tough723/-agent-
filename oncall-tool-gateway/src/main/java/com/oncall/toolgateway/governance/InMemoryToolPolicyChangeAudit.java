package com.oncall.toolgateway.governance;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 内存实现，供单实例 / 本地 / S0 影子模式起步。生产应落库。 */
public final class InMemoryToolPolicyChangeAudit implements ToolPolicyChangeAudit {

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    @Override
    public void recordApplied(ToolPolicyChangeTicket ticket, String actor, Outcome outcome) {
        entries.add(new Entry(ticket.change().toolName(), actor, outcome,
                ticket.reason(), ticket.change().describe(), System.currentTimeMillis()));
    }

    @Override
    public void recordRejected(ToolPolicyChangeTicket ticket, String actor, String reason) {
        entries.add(new Entry(ticket.change().toolName(), actor, Outcome.REJECTED,
                reason, ticket.change().describe(), System.currentTimeMillis()));
    }

    @Override
    public void recordProposed(ToolPolicyChangeTicket ticket) {
        entries.add(new Entry(ticket.change().toolName(), ticket.requester(), Outcome.PROPOSED,
                ticket.reason(), ticket.change().describe(), System.currentTimeMillis()));
    }

    @Override
    public List<Entry> history(String toolName) {
        return entries.stream()
                .filter(e -> e.toolName().equals(toolName))
                .sorted(Comparator.comparingLong(Entry::atMillis))
                .toList();
    }

    @Override
    public List<Entry> recent(int limit) {
        if (limit <= 0) {
            return List.of();
        }
        return entries.stream()
                .sorted(Comparator.comparingLong(Entry::atMillis).reversed())
                .limit(limit)
                .toList();
    }

    /** 测试辅助。 */
    public int size() {
        return entries.size();
    }
}
