package com.oncall.alert;

import com.oncall.domain.alert.AlertEvent;
import com.oncall.domain.alert.AlertGroup;

import java.util.Optional;

/**
 * 告警聚合组与原始事件的存取端口。
 *
 * <h2>★ 为什么没有 {@code insertGroup}：一次 CI 红换来的</h2>
 * <p>本接口第一版有 {@code insertGroup(AlertGroup)}，配合 {@code ingest} 使用。
 * 那是<b>错的</b>，而且第一次跑就红了：{@code AlertGroup.open()} 把
 * {@code eventCount} 起在 1（DDL 默认值，语义是「创建这个组的那条事件」），
 * 但 {@code insertGroup} <b>不会</b>插入那条事件行；随后 {@code ingest}
 * 又插入一行并 {@code +1}。于是 {@code event_count = 2} 而真实行数是 1 ——
 * 从第一次调用起就漂移了。
 *
 * <p>本接口 javadoc 当时正写着「让 {@code event_count} 与真实事件数不可能漂移」，
 * 而实现做的恰好相反。测试断言 {@code event_count == countEventsInGroup(...)}，
 * 所以它红了。<b>是断言抓到了实现，不是断言写错了。</b>
 *
 * <p>修法不是把数字调对，而是<b>把这个形状去掉</b>：组只能由它的第一条事件创建。
 * 于是「计数为 1 但零行事件」这个状态在结构上不可能存在——
 * 没有任何方法能造出它。
 *
 * <h2>★ 为什么 {@link #ingest} 必须是一个原子操作</h2>
 * <p>{@code alert_event.group_id} 在 V3 里<b>没有外键</b>。这不是疏忽：
 * 两张表都是 {@code PARTITION BY RANGE}，而 PostgreSQL 不支持
 * 指向分区表的跨分区外键。于是「{@code event_count} 必须等于该组真实行数」
 * 这件本该由数据库保证的事，数据库管不了。
 *
 * <p>而 V3 的文件头写着「聚合率是这个系统的<b>第一成本杠杆</b>」，
 * {@code aggregation_ratio = 1 - (事件数 / 原始告警数)} 的唯一数据来源就是
 * {@code event_count}。它是<b>反规范化计数器</b>：若插入事件与递增计数
 * 分成两次提交，中间崩一次计数就永久少 1，聚合率会安静地偏高，
 * 成本报表不会有任何异常。
 *
 * <h2>为什么还要 {@link #countEventsInGroup}</h2>
 * <p>有了原子写入，仍然要能<b>量出</b>漂移。计数器与真实行数是否一致
 * 是一个可以被检查的事实，不该只是一个被相信的假设——
 * 尤其当它支撑的是一级成本指标。本轮就是靠它抓到缺陷的。
 */
public interface AlertStore {

    /**
     * 原子地接入一条事件：写入事件，并让所属组的计数 +1（组不存在则创建）。
     *
     * <p><b>{@code group} 是「若需创建时使用的组描述」</b>：调用方应当用
     * {@code AlertGroup.open(id, fingerprint, service, severity, event.firedAt())}
     * 构造它。只有在该组<b>尚不存在</b>时，它的
     * {@code firstSeenAt} / {@code lastSeenAt} / {@code eventCount} 才会被写入；
     * 组已存在时只用到 {@code id}，计数与「最后出现」由本法推进——
     * 因为调用方通常并不知道这个组最初是什么时候出现的。
     *
     * @return {@code true} 表示这是一条新事件；{@code false} 表示该事件已存在
     *         （同一条告警被重复投递），此时计数<b>不</b>递增——
     *         否则重复投递会把聚合率算低，那正好与真实情况相反
     * @throws IllegalStateException 真正的数据库故障。必须与「重复投递」可区分
     */
    boolean ingest(AlertGroup group, AlertEvent event);

    /**
     * 按 {@code id} 查组。
     *
     * <p>主键是复合的 {@code (id, first_seen_at)}，但查询只按 {@code id}：
     * 调用方手里通常只有 id。这要求 {@code id} 在应用层全局唯一
     * （DDL 层面同一个 id 配不同 {@code first_seen_at} 是两行，
     * 数据库不会替你保证）。
     */
    Optional<AlertGroup> findGroup(String id);

    /** 该组的真实事件行数。用于核对 {@code event_count} 是否已经漂移。 */
    int countEventsInGroup(String groupId);

    /** 只改状态。计数与两个时刻由 {@link #ingest} 推进，不在此列。 */
    void updateGroupStatus(String groupId, com.oncall.domain.alert.AlertStatus status);
}
