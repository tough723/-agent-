package com.oncall.ontology.rule;

import com.oncall.ontology.Criticality;
import com.oncall.ontology.Ontology;

/**
 * R3：目标服务是「资源类告警」且关键度 CRITICAL ⇒ 放权上限 S1（建议，不自动执行）。
 *
 * <p><b>这条规则用到了 is-a 推理</b>，也是本体真正发挥作用的地方：
 * 判断「这个告警是不是资源类」要沿 is-a 上溯，
 * 而「资源类告警」是个**刚性**分类（磁盘告警就是磁盘告警，不会变成别的），
 * 所以它可以做成子类划分——这与 {@code criticality} 的处理正好相反。
 *
 * <p>有界遍历（最多 {@link Ontology#MAX_ISA_DEPTH} 层）而非推理机：
 * 这里需要的是「A 是不是 B 的子孙」，一个有向图可达性问题。
 */
public final class ResourceAlertOnCriticalServiceCapsAutonomy implements OntologyRule {

    public static final String ID = "R3";

    /** 资源类告警的概念 id。 */
    public static final String RESOURCE_ALERT = "alert:resource";

    /** 放权上限。S1 = 只给建议，不自动执行。 */
    public static final String AUTONOMY_CAP = "S1";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "资源类告警 + 关键度 CRITICAL ⇒ 放权上限 " + AUTONOMY_CAP;
    }

    @Override
    public void evaluate(Ontology ontology, RuleContext context, RuleEffect effect) {
        if (context.serviceId() == null || context.alertConceptId() == null) {
            return;
        }
        if (ontology.criticalityOf(context.serviceId()) != Criticality.CRITICAL) {
            return;
        }
        if (ontology.isA(context.alertConceptId(), RESOURCE_ALERT)) {
            effect.capAutonomy(AUTONOMY_CAP);
            effect.markFired(ID);
        }
    }
}
