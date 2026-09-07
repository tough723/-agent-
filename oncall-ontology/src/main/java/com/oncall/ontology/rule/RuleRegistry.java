package com.oncall.ontology.rule;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 规则注册表 —— {@code onto_rule} 表的读写口。
 *
 * <h2>★ 这张表管的是「开关与统计」，不是规则本体</h2>
 * <p>规则本体在 Java（{@link OntologyRule} 的四个实现类）。表注释写得很清楚：
 * 「规则本体在 Java，这里只做开关与统计」。所以本接口<b>不加载规则逻辑</b>，
 * 只回答两个问题：<b>哪条被停用了</b>、<b>哪条真的在命中</b>。
 *
 * <h2>★ 为什么需要它：一个已经存在但无人持久化的能力</h2>
 * <p>{@link RuleEngine} 早就接受 {@code disabledRuleIds} 参数，
 * 它的 javadoc 也明写「对应 {@code onto_rule.enabled = FALSE}」——
 * 但<b>此前没有任何代码读写这张表</b>。也就是说：
 * 停用一条安全规则的能力存在，却没有任何持久化记录说明
 * <b>哪条被停用了、什么时候停的</b>。重启即失忆。
 *
 * <h2>★ 三条不可让步的语义</h2>
 * <ol>
 *   <li><b>表里没有行 = 规则启用。</b> 反过来（缺行=停用）会让一条安全规则
 *       因为「没人插过行」而静默关闭 —— 而这四条规则全都是<b>收紧</b>约束的
 *       （两人审批、放权上限）。安全默认值必须是「生效」。</li>
 *   <li><b>未知规则 id 在写入时就被拒绝。</b> {@link RuleEngine} 只能在求值时
 *       发一条警告，而那时已经太晚了：运维以为自己关掉了某条规则，
 *       真正那条照常在跑。写入时拒绝才拦得住。</li>
 *   <li><b>命中计数不碰 {@code updated_at}。</b> {@code updated_at} 的语义是
 *       「开关最后一次被人改动的时间」。如果每次命中都刷新它，
 *       「这条规则是三个月前被停用的」这个信息就被冲掉了 ——
 *       而那正是复盘时最需要知道的事。</li>
 * </ol>
 */
public interface RuleRegistry {

    /**
     * 当前被停用的规则 id 集合，直接喂给
     * {@link RuleEngine#RuleEngine(List, Set)}。
     *
     * <p><b>只返回 {@code enabled = FALSE} 的行。</b>没有行的规则视为启用 ——
     * 见类注释第 1 条。
     */
    Set<String> disabledRuleIds();

    /**
     * 启用或停用一条规则。
     *
     * @throws IllegalArgumentException 该 id 在表里不存在。
     *         <b>刻意不自动建行</b>：自动建行等于接受一个可能是拼写错误的 id，
     *         而后果是「以为关掉了，其实没关」。先 {@link #syncKnownRules} 再改开关。
     */
    void setEnabled(String ruleId, boolean enabled);

    /**
     * 累加命中次数。入参取自 {@link RuleEffect#firedRuleIds()}。
     *
     * <p>{@code onto_rule.hit_count} 的列注释是：「长期为 0 的规则说明它没用，
     * 应该删掉而不是留着」。<b>没有写入方，那句话永远无法被执行</b> ——
     * 所有规则的 hit_count 都停在 0，于是「哪条规则其实没用」这个问题
     * 永远得不到答案，只能靠猜。
     *
     * <p>未知 id 静默忽略而<b>不</b>抛异常：命中统计是观测数据，
     * 不该让一次统计失败把已经算出来的安全结论一起带崩。
     */
    void recordHits(Collection<String> firedRuleIds);

    /**
     * 把引擎里已知的规则同步进表（只补 id 与 description）。
     *
     * <p><b>绝不改动 {@code enabled}。</b> 启动时重新同步是常态，
     * 如果同步会把 {@code enabled} 重置成默认值 TRUE，
     * 那么运维刻意停用的规则会在每次重启后自己复活 ——
     * 这比「停用不生效」更危险，因为它看起来生效过。
     */
    void syncKnownRules(List<OntologyRule> rules);
}
