package com.oncall.domain.alert;

import com.oncall.domain.autonomy.AlertSeverity;

import java.time.Instant;
import java.util.Objects;

/**
 * 单条原始告警 —— {@code alert_event} 表的一行。8 个组件对应 V3 的 8 列。
 *
 * <h2>★ {@code rawPayload} 的合法性由数据库把关，不是本类</h2>
 * <p>V3 把它定为 {@code JSONB NOT NULL}，并写明保留原文的理由：
 * 「聚合规则会调整，需要能重放历史告警验证新规则」。
 *
 * <p><b>本类不校验它是不是合法 JSON，这是刻意的</b>：{@code oncall-domain}
 * 是零依赖模块（F2 禁止 {@code com.fasterxml..}），没有 JSON 解析器可用。
 * 与其引一个依赖进来做半套校验，不如让 PostgreSQL 的 {@code JSONB} 类型
 * 在插入时把关——它是唯一真正权威的那一个。
 * 所以「非法 payload 会被拒绝而不是静默存进去」这件事
 * 由 {@code JdbcAlertEventStoreTest} 在真实库上验证，不在这里假装。
 *
 * <p>本类只守它<b>能</b>守的：非空、非空白。空白串能通过 NOT NULL
 * 却会在重放时炸掉，所以要在构造期就拒。
 *
 * <h2>两个时刻的分工</h2>
 * <p>{@code fired_at} 是告警在源头发生的时刻，{@code received_at} 是我们收到的时刻。
 * 两者的差就是<b>接入延迟</b>，而 {@code received_at} 同时是分区键。
 * 刻意不做「{@code received_at} 必须晚于 {@code fired_at}」的校验：
 * 源端时钟快几分钟是常见故障，那种情况下拒绝这条告警等于<b>丢告警</b>，
 * 而 V3 明确写了「丢一条告警比多一次 VACUUM 严重得多」。
 * 时钟倒挂应当被记下来排查，不该让告警消失。
 */
public record AlertEvent(
        String id,
        String groupId,
        String source,
        String rawPayload,
        String labels,
        AlertSeverity severity,
        Instant firedAt,
        Instant receivedAt) {

    public static final int MAX_ID_LENGTH = 64;
    public static final int MAX_SOURCE_LENGTH = 64;

    public AlertEvent {
        requireFits(id, "id", MAX_ID_LENGTH);
        requireFits(groupId, "groupId", MAX_ID_LENGTH);
        requireFits(source, "source", MAX_SOURCE_LENGTH);
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(firedAt, "firedAt");
        Objects.requireNonNull(receivedAt, "receivedAt");

        // raw_payload 是 NOT NULL。空白串能过 NOT NULL 却会在重放时炸掉。
        Objects.requireNonNull(rawPayload, "rawPayload：raw_payload 是 NOT NULL 列");
        if (rawPayload.isBlank()) {
            throw new IllegalArgumentException(
                    "rawPayload 不得为空白——它能通过 NOT NULL 检查，却会在重放历史告警时失败");
        }
        // labels 可空；但「给了却全是空白」等同于数据缺陷，不是「没有标签」。
        if (labels != null && labels.isBlank()) {
            throw new IllegalArgumentException(
                    "labels 要么是 null（没有标签）要么是有效内容，空白串两者都不是");
        }
    }

    private static void requireFits(String value, String field, int max) {
        Objects.requireNonNull(value, field + "：不得为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "：不得为空白");
        }
        // 绝不截断：截断后的 group_id 会把事件挂到另一个组上。
        if (value.length() > max) {
            throw new IllegalArgumentException(field + " 超过列宽 " + max
                    + "（实际 " + value.length() + "）——绝不截断，截断会把事件挂到别的组上");
        }
    }

    /** 接入延迟。为负说明源端时钟快于本地，应当被记下来排查而不是丢弃。 */
    public java.time.Duration ingestionLag() {
        return java.time.Duration.between(firedAt, receivedAt);
    }

    /** 源端时钟是否倒挂（本地收到的时刻早于源头发生的时刻）。 */
    public boolean hasClockSkew() {
        return receivedAt.isBefore(firedAt);
    }
}
