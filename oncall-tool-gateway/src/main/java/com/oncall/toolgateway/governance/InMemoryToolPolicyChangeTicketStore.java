package com.oncall.toolgateway.governance;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现。<b>仅供单实例、本地开发与测试使用</b>——理由同
 * {@code InMemoryToolExecutionLedger}：多实例下 A 实例发起的单子 B 实例看不见，
 * 双人复核会退化成"必须找对那台机器"。
 */
public final class InMemoryToolPolicyChangeTicketStore implements ToolPolicyChangeTicketStore {

    private final Map<String, ToolPolicyChangeTicket> tickets = new ConcurrentHashMap<>();

    @Override
    public void put(ToolPolicyChangeTicket ticket) {
        tickets.put(ticket.id(), ticket);
    }

    @Override
    public Optional<ToolPolicyChangeTicket> find(String id) {
        return Optional.ofNullable(tickets.get(id));
    }

    @Override
    public void remove(String id) {
        tickets.remove(id);
    }

    @Override
    public List<ToolPolicyChangeTicket> open() {
        // 显式排序：ConcurrentHashMap 的迭代顺序不保证，
        // 而这份列表会进界面与提示语，顺序抖动看起来就像"单子凭空换位置了"。
        return tickets.values().stream()
                .sorted(Comparator.comparingLong(ToolPolicyChangeTicket::createdAtMillis)
                        .thenComparing(ToolPolicyChangeTicket::id))
                .toList();
    }

    /** 测试辅助。 */
    public int size() {
        return tickets.size();
    }
}
