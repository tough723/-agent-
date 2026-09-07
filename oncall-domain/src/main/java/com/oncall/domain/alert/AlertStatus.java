package com.oncall.domain.alert;

/**
 * 告警聚合组的状态。
 *
 * <p><b>取值转录自 DDL，不是本类首次定义</b>：
 * {@code db/migration/V3__alert.sql} 的列注释原文是
 * {@code COMMENT ON COLUMN alert_group.status IS 'OPEN / ACKED / RESOLVED / SUPPRESSED'}。
 * 与 {@code StepStatus}（DDL 无取值注释，取值由列结构推导）不同，
 * 这里四个值逐字来自那份注释，改动必须同时改 DDL。
 *
 * <p><b>为什么不用字符串</b>：这四个值此前只活在一条 SQL 注释里，
 * Java 侧没有类型承载。字符串状态下拼错不会有编译器报错，
 * 只会在查询「还有哪些未处理告警」时静默少算。
 */
public enum AlertStatus {

    /** 未确认。{@code idx_alert_group_open (status, last_seen_at DESC)} 就是为查这一态建的。 */
    OPEN,

    /** 已有人认领，但故障尚未消除。 */
    ACKED,

    /** 已消除。 */
    RESOLVED,

    /**
     * 被抑制（维护窗口、已知重复等）。
     *
     * <p><b>抑制不是消除</b>：SUPPRESSED 的组仍然占着一条记录，
     * 统计「真实故障数」时它与 RESOLVED 不是一回事，不能合并计。
     */
    SUPPRESSED;

    /** 是否仍需人处理。只有 OPEN 与 ACKED 需要。 */
    public boolean needsAttention() {
        return this == OPEN || this == ACKED;
    }
}
