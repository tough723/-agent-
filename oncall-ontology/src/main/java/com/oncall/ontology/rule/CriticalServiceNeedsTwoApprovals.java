package com.oncall.ontology.rule;

import com.oncall.ontology.Criticality;
import com.oncall.ontology.Ontology;

/**
 * R2：目标服务是关键度 CRITICAL 且操作为高危 ⇒ 至少两人审批。
 *
 * <p><b>这条规则是 OntoClean 检查的直接产物。</b>
 * 它读的是 {@code criticality} **属性**，而不是问「这个服务是不是 CoreService 子类的实例」。
 * 如果按子类建模，这条规则要写成类型判断，
 * 而且服务升级为「核心」时必须迁移实例类型——运维上不可能接受。
 *
 * <p>注意它**不做 is-a 推理**：关键度是每个概念自己的属性，
 * 不从父类继承。理由：一个业务域标为 CRITICAL，不代表域里每个服务都是 CRITICAL。
 * 如果继承，标错一个父节点会连带把几十个子服务变成两人审批。
 */
public final class CriticalServiceNeedsTwoApprovals implements OntologyRule {

    public static final String ID = "R2";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "关键度 CRITICAL 的服务 + 高危操作 ⇒ 至少两人审批";
    }

    @Override
    public void evaluate(Ontology ontology, RuleContext context, RuleEffect effect) {
        if (context.serviceId() == null || !context.isHighRisk()) {
            return;
        }
        if (ontology.criticalityOf(context.serviceId()) == Criticality.CRITICAL) {
            effect.requireApprovers(2);
            effect.markFired(ID);
        }
    }
}
