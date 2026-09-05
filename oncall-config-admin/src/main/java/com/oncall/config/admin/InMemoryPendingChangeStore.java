package com.oncall.config.admin;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单实例用的内存实现。多实例部署必须换成数据库实现，
 * 理由见 {@link PendingChangeStore}。
 *
 * <p>过期项采用<b>惰性清理</b>：读取时判断，不额外起清理线程。
 * 待复核变更的数量受高危键数量限制（当前 5 个），不会无限增长。
 */
public final class InMemoryPendingChangeStore implements PendingChangeStore {

    private final Map<String, PendingChange> byId = new ConcurrentHashMap<>();

    @Override
    public void put(PendingChange change) {
        byId.put(change.id(), change);
    }

    /**
     * 原样返回，<b>不因过期而过滤</b>。
     *
     * <p>"不存在"和"已过期"对调用方是两种不同的处置（404 vs 410），
     * 在这里合并掉就没法区分了。过期判断留给 controller。
     */
    @Override
    public Optional<PendingChange> find(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public void remove(String id) {
        byId.remove(id);
    }

    /** 测试用：当前未过期的待复核数量。 */
    public int size() {
        long now = System.currentTimeMillis();
        return (int) byId.values().stream().filter(c -> !c.isExpired(now)).count();
    }
}
