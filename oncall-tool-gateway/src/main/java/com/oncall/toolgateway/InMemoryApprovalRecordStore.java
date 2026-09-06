package com.oncall.toolgateway;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版审批记录存储。
 *
 * <p>用途：单元测试、本地开发。
 * <b>生产必须换成 {@link JdbcApprovalRecordStore}</b>——这张表的保留期是永久的、
 * 是责任归属的唯一凭据，进程一重启就没了不是「审计不全」，是责任无法追溯。
 *
 * <p>{@link #decide} 的原子性与 JDBC 版保持一致：
 * 用 {@code compute} 让「读当前状态 + 改状态」成为一次原子操作，
 * 否则两个审批人同时点批准就会都返回 {@code true}，
 * 而 JDBC 版靠 {@code WHERE decision='PENDING'} 保证只有一个生效——
 * 两个实现的语义必须一样，否则测试通过而线上行为不同。
 */
public class InMemoryApprovalRecordStore implements ApprovalRecordStore {

    private final Map<String, ApprovalRecord> rows = new ConcurrentHashMap<>();

    @Override
    public void insert(ApprovalRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record 不能为 null");
        }
        if (rows.putIfAbsent(record.id(), record) != null) {
            throw new IllegalArgumentException("审批记录 id 已存在：" + record.id());
        }
    }

    @Override
    public boolean decide(String id, ApprovalDecision outcome, String approver, String comment) {
        if (id == null || outcome == null || !outcome.isFinal()) {
            throw new IllegalArgumentException("id 与终态结论都不能为空，收到：" + outcome);
        }
        // compute 是原子的：这正是 JDBC 版 WHERE decision='PENDING' 的内存等价物。
        boolean[] applied = {false};
        rows.computeIfPresent(id, (k, current) -> {
            if (current.decision() != ApprovalDecision.PENDING) {
                return current;      // 已被别人决定，保持原样
            }
            applied[0] = true;
            return current.decided(outcome, approver, comment);
        });
        return applied[0];
    }

    @Override
    public Optional<ApprovalRecord> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(rows.get(id));
    }

    @Override
    public List<ApprovalRecord> pending() {
        return rows.values().stream()
                .filter(r -> r.decision() == ApprovalDecision.PENDING)
                .sorted(Comparator.comparing(ApprovalRecord::requestedAt))
                .toList();
    }

    @Override
    public List<ApprovalRecord> pendingRequestedBefore(Instant requestedBefore) {
        if (requestedBefore == null) {
            throw new IllegalArgumentException("requestedBefore 不能为 null");
        }
        return rows.values().stream()
                .filter(r -> r.decision() == ApprovalDecision.PENDING)
                .filter(r -> r.requestedAt().isBefore(requestedBefore))
                .sorted(Comparator.comparing(ApprovalRecord::requestedAt))
                .toList();
    }

    @Override
    public int count() {
        return rows.size();
    }

    /** 测试辅助。 */
    public List<ApprovalRecord> all() {
        return new ArrayList<>(rows.values());
    }

    public void clear() {
        rows.clear();
    }
}
