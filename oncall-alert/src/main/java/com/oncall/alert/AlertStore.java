package com.oncall.alert;

import com.oncall.domain.alert.AlertEvent;
import com.oncall.domain.alert.AlertGroup;

import java.util.Optional;

/**
 * 告警聚合组与原始事件的存取端口。
 *
 * <h2>★ 为什么 {@link #ingest} 必须是一个原子操作</h2>
 * <p>{@code alert_event.group_id} 在 V3 里<b>没有外键</b>。这不是疏忽：
 * 两张表都是 {@code PARTITION BY RANGE}，而 PostgreSQL 不支持
 * 指向分区表的跨分区外键。于是两件本该由数据库保证的事，
 * 数据库一件都管不了：
 * <ol>
 *   <li>事件所属的组必须真实存在；</li>
 *   <li>{@code alert_group.event_count} 必须等于该组的真实事件行数。</li>
 * </ol>
 *
 * <p>第 2 条尤其要紧。V3 的文件头写着「聚合率是这个系统的<b>第一成本杠杆</b>」，
 * 而 {@code aggregation_ratio = 1 - (事件数 / 原始告警数)} 的唯一数据来源
 * 就是 {@code event_count}。<b>它是一个反规范化计数器</b>——
 * 如果插入事件与递增计数分成两次提交，中间崩一次，计数就永久少 1，
 * 而聚合率会安静地偏高，成本报表不会有任何异常。
 *
 * <p>所以 {@code ingest} 必须在<b>同一个事务</b>里完成两件事，
 * 并且由测试直接验证「事务回滚时两边都不留痕迹」。
 *
 * <h2>为什么还要 {@link #countEventsInGroup}</h2>
 * <p>有了原子写入，仍然要能<b>量出</b>漂移。计数器与真实行数是否一致
 * 是一个可以被检查的事实，而不该只是一个被相信的假设——
 * 尤其当它支撑的是一级成本指标。
 */
public interface AlertStore {

    /** 新建一个聚合组。 */
    void insertGroup(AlertGroup group);

    /**
     * 主键是复合的 {@code (id, first_seen_at)}，所以查询也要两个参数。
     * 分区表的分区键必须包含在主键里，光有 {@code id} 定位不到行。
     */
    Optional<AlertGroup> findGroup(String id, java.time.Instant firstSeenAt);

    /**
     * 原子地接入一条事件：写入事件 + 递增所属组的计数与最后出现时刻。
     *
     * @return {@code true} 表示这是一条新事件；{@code false} 表示该事件已存在
     *         （同一条告警被重复投递），此时计数<b>不</b>递增——
     *         否则重复投递会把聚合率算低，那正好与真实情况相反
     * @throws IllegalStateException 组不存在，或真正的数据库故障。
     *         两者必须都与「重复投递」可区分
     */
    boolean ingest(AlertGroup group, AlertEvent event);

    /** 该组的真实事件行数。用于核对 {@code event_count} 是否已经漂移。 */
    int countEventsInGroup(String groupId);

    /** 只改状态。计数与两个时刻是写入一次的，不在此列。 */
    void updateGroupStatus(AlertGroup group);
}
