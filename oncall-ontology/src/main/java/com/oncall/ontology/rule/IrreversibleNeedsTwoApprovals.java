package com.oncall.ontology.rule;

import com.oncall.ontology.Ontology;

/**
 * R1：不可逆操作 ⇒ 至少两人审批。
 *
 * <p>这是四条规则里**唯一一条与本体无关**的——它只看操作本身是否可逆。
 * 放在这里是为了让全部放权约束在一处可见，而不是散在三个地方。
 *
 * <p><b>可逆性是刚性属性</b>：一个操作可逆，就是可逆的，不随时间或上下文变化。
 * 这与 {@code criticality} 相反——所以可逆性可以建成分类，关键度不行。
 */
public final class IrreversibleNeedsTwoApprovals implements OntologyRule {

    public static final String ID = "R1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "不可逆操作 ⇒ 至少两人审批";
    }

    @Override
    public void evaluate(Ontology ontology, RuleContext context, RuleEffect effect) {
        if (context.irreversible()) {
            effect.requireApprovers(2);
            effect.markFired(ID);
        }
    }
}
