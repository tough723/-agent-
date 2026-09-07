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
 * <p><b>谁真正在增加计数：不是本类，是 SQL。</b>
 * 本类刻意不提供 {@code withEventCount(int)} 之类的写法——那样就能造出
 * 「计数是 7 但最后出现还停在第一条」的组。但要说清楚的是：
 * {@link #absorb} 目前<b>没有任何生产调用点</b>，
 * 生产路径上的 {@code +1} 发生在 {@code JdbcAlertStore} 的
 * {@code UPDATE ... SET event_count = event_count + 1} 里。
 *
 * <p>这不是疏忽，而是<b>并发下的必然</b>：若由 Java 侧算好新值再写回，
 * 两个 worker 同时接入同一个组时会都读到 3、都写 4，丢一次更新；
 * 而 {@code event_count + 1} 由数据库在同一行上串行执行，不会丢。
 * {@code absorb} 保留下来是给<b>单实例、内存态</b>的调用方用的
 * （例如将来的编排层在内存里累积一个组），它不是持久化路径。
 *
 * <p><b>两处对「乱序事件」的规定目前不一致，这是已知缺口。</b>
 * {@code absorb} 遇到早于 {@code lastSeenAt} 的事件<b>抛异常</b>；
 * 而 SQL 用 {@code GREATEST(last_seen_at, ?)} <b>静默接受</b>，只是不把
 * 「最后出现」往回推。两者都守住了「最后出现不倒退」，
 * 但对「要不要拒绝这条告警」给了相反的答案。
 * 持久层选择接受，依据是 V3 的「丢一条告警比多一次 VACUUM 严重得多」；
 * 领域层的抛异常则更适合内存态下的调用方自查。
 * <b>在编排层落地之前不要贸然统一这两者</b>——统一成抛异常就会开始丢告警。
 *
 * <p>至于「组内事件行数必须等于 {@code eventCount}」，它跨了两张表，
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
