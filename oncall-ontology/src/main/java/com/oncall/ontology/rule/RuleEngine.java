package com.oncall.ontology.rule;

import com.oncall.ontology.Ontology;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 规则求值器。四条规则顺序执行，效果累加。
 *
 * <p><b>顺序执行而非规则引擎</b>，两个原因：
 * <ul>
 *   <li>四条规则用 Drools / Easy Rules 都是过度设计；</li>
 *   <li><b>安全相关的判定必须可预测</b>。规则引擎的冲突消解策略
 *       （salience / specificity）会引入执行顺序的不确定性，
 *       而这里「两条规则同时触发时取更严格的那档」必须由代码显式保证。</li>
 * </ul>
 *
 * <p>规则集合是构造时固定的。刻意**不提供运行时增删规则的接口**：
 * 放权约束属于变更受控项，走配置变更审批流程，不走热更新。
 */
public final class RuleEngine {

    private final List<OntologyRule> rules;
    private final Set<String> disabledRuleIds;

    /** 默认四条规则。 */
    public RuleEngine() {
        this(List.of(
                new IrreversibleNeedsTwoApprovals(),
                new CriticalServiceNeedsTwoApprovals(),
                new ResourceAlertOnCriticalServiceCapsAutonomy(),
                new StaleRunbookMustBeFlagged()
        ));
    }

    public RuleEngine(List<OntologyRule> rules) {
        this(rules, Set.of());
    }

    /**
     * @param disabledRuleIds 被停用的规则 id，对应 {@code onto_rule.enabled = FALSE}。
     *
     * <p><b>刻意不加成 {@code RuleEngine(Set)} 单参重载</b>——
     * 那会与上面的 {@code RuleEngine(List)} 同元数、仅参数类型不同，
     * 于是已有的 {@code new RuleEngine(null)} 会变成歧义调用。
     * 轨道 C4 已经因为同样的原因红过一次。
     *
     * <p>仍然符合类注释里「不提供运行时增删规则的接口」：
     * 这是<b>构造期</b>参数，不是热更新——停用一条规则依然要走变更审批再重新装配。
     */
    public RuleEngine(List<OntologyRule> rules, Set<String> disabledRuleIds) {
        this.rules = List.copyOf(rules);
        Objects.requireNonNull(disabledRuleIds, "disabledRuleIds");
        this.disabledRuleIds = Set.copyOf(disabledRuleIds);
    }

    /**
     * 求值。
     *
     * <p>单条规则抛异常**不会**中断其余规则——某条规则的数据缺失
     * 不应该让其他约束一起失效。
     *
     * <p><b>但「跳过」绝不能等于「放过」。</b>本方法此前只往 {@code warnings} 里
     * 加一个字符串，而 {@link RuleEffect} 的效果全是<b>收紧</b>方向的
     * （{@code requireApprovers} 取更大、{@code capAutonomy} 取更严）——
     * 于是<b>一条收紧型规则求值失败，它那份收紧就凭空消失，
     * 结果比真实情况更宽松，而调用方从数值上完全看不出来</b>。
     * 默认四条规则里有三条是收紧型的（两条 {@code requireApprovers}、
     * 一条 {@code capAutonomy}），所以这不是边角情况。
     *
     * <p>而「加个警告」不构成防线：警告需要有人读，
     * 而「谁来读」在类型上没有任何约束（{@code warnings()} 在生产代码里
     * 一个读者都没有）。
     *
     * <p>所以现在失败会走 {@link RuleEffect#markDegraded}，
     * <b>把效果直接压到保守下限</b>（两人审批 + 放权上限 S1），
     * 调用方即使完全不看警告也拿不到过宽的结论。
     */
    public RuleEffect evaluate(Ontology ontology, RuleContext context) {
        RuleEffect effect = RuleEffect.none();
        List<String> errors = new ArrayList<>();
        for (OntologyRule rule : rules) {
            if (disabledRuleIds.contains(rule.id())) {
                // 在求值<b>之前</b>就跳过：被停用的规则连跑都不该跑。
                // 这个顺序有实际后果——一条既被停用又会抛异常的规则
                // 不会被记成降级，因为它根本没执行。
                //
                // 而 markDisabled 与 markDegraded 的方向相反：
                // 停用是人的决定，绝不能压保守下限（压了停用就永远不生效）；
                // 降级是系统不知道答案，必须压下限。
                effect.markDisabled(rule.id());
                continue;
            }
            try {
                rule.evaluate(ontology, context, effect);
            } catch (RuntimeException e) {
                errors.add(rule.id() + ": " + e.getClass().getSimpleName());
                // 保守下限压进效果本身，而不是只留一条等人来读的警告。
                effect.markDegraded(rule.id());
            }
        }
        if (!errors.isEmpty()) {
            effect.warn("以下规则求值失败，已按保守下限处理（两人审批 + 放权上限 "
                    + RuleEffect.CONSERVATIVE_AUTONOMY_CAP + "），请人工确认："
                    + String.join(", ", errors));
        }
        for (String id : disabledRuleIds) {
            boolean known = false;
            for (OntologyRule rule : rules) {
                if (rule.id().equals(id)) {
                    known = true;
                    break;
                }
            }
            if (!known) {
                // 库里写了一个引擎里不存在的 id，最可能是拼写错误。
                // 后果是具体的：运维以为自己关掉了某条规则，
                // 而真正那条规则照常在跑——静默失效，且没有任何报错。
                effect.warn("停用列表中的 " + id + " 不是本引擎已知的规则 id，"
                        + "可能是拼写错误；对应规则并未被停用");
            }
        }
        return effect;
    }

    /** 已加载的规则数，用于启动自检。 */
    public int size() {
        return rules.size();
    }
}
