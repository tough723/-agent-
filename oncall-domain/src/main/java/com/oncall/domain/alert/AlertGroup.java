package com.oncall.domain.alert;

import com.oncall.domain.autonomy.AlertSeverity;

import java.time.Instant;
import java.util.Objects;

/**
 * 告警聚合组 —— {@code alert_group} 表的一行。9 个组件对应 V3 的 9 列。
 *
 * <h2>★ {@code eventCount} 是聚合率的唯一数据来源</h2>
 * <p>V3 的文件头原文：「聚合率是这个系统的<b>第一成本杠杆</b>，不是可靠性优化。
 * 聚合率从 15% 恶化到 40%，月 LLM 成本涨 2.7 倍」，并给出
 * {@code aggregation_ratio = 1 - (事件数 / 原始告警数)}。
 *
 * <p>也就是说 {@code event_count} 是<b>反规范化计数器</b>，而一级成本指标压在它身上。
 * 反规范化计数器会漂移：如果它能与 {@code alert_event} 的真实行数不一致，
 * 那么聚合率就是在算一个假数，而成本报表不会有任何异常。
 *
 * <p>本类能做的部分：<b>让 {@code eventCount} 与 {@code lastSeenAt} 只能一起动。</b>
 * 唯一能增加计数的方法是 {@link #absorb}，它同时把「最后出现时刻」推到新事件上。
 * 刻意不提供 {@code withEventCount(int)} 之类的写法——
 * 那样就能造出「计数是 7 但最后出现还停在第一条」的组。
 *
 * <p>剩下的部分（组内事件行数必须等于 {@code eventCount}）跨了两张表，
 * 只能由存储层在<b>同一个事务</b>里保证，见 {@code JdbcAlertStore}。
 *
 * <h2>主键是复合的</h2>
 * <p>DDL 是 {@code PRIMARY KEY (id, first_seen_at)} —— 分区表的分区键
 * 必须包含在主键里。所以「{@code id} 唯一」这句话在数据库层面<b>不成立</b>：
 * 同一个 {@code id} 配两个不同的 {@code first_seen_at} 是两行。
 * 应用层要保证 {@code id} 全局唯一，不能指望数据库。
 */
public record AlertGroup(
        String id,
        String fingerprint,
        String service,
        AlertSeverity severity,
        AlertStatus status,
        int eventCount,
        Instant firstSeenAt,
        Instant lastSeenAt,
        String runId) {

    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_FINGERPRINT_LENGTH = 128;
    public static final int MAX_SERVICE_LENGTH = 191;

    public AlertGroup {
        requireFits(id, "id", MAX_ID_LENGTH);
        requireFits(fingerprint, "fingerprint", MAX_FINGERPRINT_LENGTH);
        if (service != null) {
            requireFits(service, "service", MAX_SERVICE_LENGTH);
        }
        if (runId != null) {
            requireFits(runId, "runId", MAX_ID_LENGTH);
        }
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(firstSeenAt, "firstSeenAt");
        Objects.requireNonNull(lastSeenAt, "lastSeenAt");

        // DDL 的默认值是 1：一个组至少含一条事件，否则它不该存在。
        if (eventCount < 1) {
            throw new IllegalArgumentException(
                    "eventCount 至少为 1（DDL 默认值即 1）：一个不含任何事件的聚合组不该存在，实际 "
                            + eventCount);
        }
        if (lastSeenAt.isBefore(firstSeenAt)) {
            throw new IllegalArgumentException("lastSeenAt=" + lastSeenAt
                    + " 早于 firstSeenAt=" + firstSeenAt
                    + "——最后出现不可能早于第一次出现");
        }
    }

    private static void requireFits(String value, String field, int max) {
        Objects.requireNonNull(value, field + "：不得为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "：不得为空白");
        }
        // 绝不截断：截断后的 fingerprint 会让两个不同的告警聚成同一组。
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " 超过列宽 " + max
                    + "（实际 " + value.length() + "）——绝不截断，截断会让主键指向别的行");
        }
    }

    /** 开一个新组：计数 1，首末出现同为这一条事件的时刻。 */
    public static AlertGroup open(String id, String fingerprint, String service,
                                  AlertSeverity severity, Instant firedAt) {
        return new AlertGroup(id, fingerprint, service, severity, AlertStatus.OPEN,
                1, firedAt, firedAt, null);
    }

    /**
     * 吸收一条新事件：计数 +1，并把「最后出现」推到这条事件上。
     *
     * <p><b>这是唯一能增加计数的入口</b>，两件事必须一起发生。
     *
     * @param firedAt 新事件的发生时刻；不得早于当前 {@code lastSeenAt}，
     *                否则「最后出现」会往回走
     */
    public AlertGroup absorb(Instant firedAt) {
        Objects.requireNonNull(firedAt, "firedAt");
        if (firedAt.isBefore(lastSeenAt)) {
            throw new IllegalArgumentException("新事件的 firedAt=" + firedAt
                    + " 早于当前 lastSeenAt=" + lastSeenAt
                    + "——乱序到达的事件不该把「最后出现」往回推");
        }
        return new AlertGroup(id, fingerprint, service, severity, status,
                eventCount + 1, firstSeenAt, firedAt, runId);
    }

    /** 改状态。刻意不改计数与时刻——状态流转与聚合是两件事。 */
    public AlertGroup withStatus(AlertStatus next) {
        Objects.requireNonNull(next, "next");
        return new AlertGroup(id, fingerprint, service, severity, next,
                eventCount, firstSeenAt, lastSeenAt, runId);
    }

    /**
     * 绑定一次排查。
     *
     * <p><b>刻意拒绝重复绑定</b>：{@code run_id} 记录的是「哪一次排查在处理这个组」，
     * 若允许覆盖，事后就无法回答「第一次是谁接的」。要换人处理应当走状态流转，
     * 而不是悄悄改掉归属。
     */
    public AlertGroup attachRun(String runIdValue) {
        if (runId != null) {
            throw new IllegalStateException("该组已绑定 run_id=" + runId
                    + "，不允许改绑——否则事后无法回答「第一次是谁接的」");
        }
        return new AlertGroup(id, fingerprint, service, severity, status,
                eventCount, firstSeenAt, lastSeenAt, runIdValue);
    }

    /** 是否仍需人处理。 */
    public boolean needsAttention() {
        return status.needsAttention();
    }
}
