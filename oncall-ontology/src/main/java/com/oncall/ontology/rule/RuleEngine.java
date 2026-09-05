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
     * 不应该让其他约束一起失效。异常规则被跳过并记入警告，
     * <b>且效果按「已触发」处理不了，所以这里选择保守方向：跳过时不放宽任何约束。</b>
     */
    public RuleEffect evaluate(Ontology ontology, RuleContext context) {
        RuleEffect effect = RuleEffect.none();
        List<String> errors = new ArrayList<>();
        for (OntologyRule rule : rules) {
            try {
                rule.evaluate(ontology, context, effect);
            } catch (RuntimeException e) {
                errors.add(rule.id() + ": " + e.getClass().getSimpleName());
            }
        }
        if (!errors.isEmpty()) {
            effect.warn("以下规则求值失败，相关约束未生效，请人工确认：" + String.join(", ", errors));
        }
        return effect;
    }

    /** 已加载的规则数，用于启动自检。 */
    public int size() {
        return rules.size();
    }
}
