package com.oncall.ontology.rule;

import com.oncall.ontology.Ontology;
import java.util.ArrayList;
import java.util.List;

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
        this.rules = List.copyOf(rules);
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
        return effect;
    }

    /** 已加载的规则数，用于启动自检。 */
    public int size() {
        return rules.size();
    }
}
