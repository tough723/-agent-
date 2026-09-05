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

    /**
     * 当前存放的待复核单数量。<b>裸计数，不掺时钟</b>。
     *
     * <p>这里曾经写成"过滤掉已过期的再计数"，结果踩了一个坑：controller 用的是
     * 注入的时钟（测试里是 1_000_000），而这里用的是 {@code System.currentTimeMillis()}
     * （约 1.7e12）。两个时钟一比，所有单都被判为过期，方法恒返回 0——
     * 断言失败的信息却指向 controller，把排查引向了错误方向。
     *
     * <p>根本原因是职责越界：过期判断已经由 {@code ConfigAdminController} 负责
     * （它才有那个可注入的时钟），store 不该再做一遍。
     */
    public int size() {
        return byId.size();
    }
}
