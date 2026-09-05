package com.oncall.ontology.rule;

import com.oncall.ontology.Ontology;

/**
 * 一条本体规则。
 *
 * <p><b>刻意不引入规则引擎</b>：四条规则用 Drools / Easy Rules 都是过度设计，
 * 而且规则引擎会引入**执行顺序不确定性**——安全相关的判定必须可预测。
 *
 * <p><b>为什么不用 SWRL</b>：SWRL 没有否定即失败，
 * 表达不了「**除非**标记为核心，**否则**一人审批」——
 * 而这恰恰是运维策略的主流形式。这不是「SWRL 不方便」，
 * 是它在语义上表达不了这个策略。
 */
public interface OntologyRule {

    String id();

    String description();

    /**
     * 评估规则。规则**不返回布尔**，而是往 {@code effect} 里累加效果——
     * 这样多条规则可以同时触发，由效果合并决定最终约束。
     */
    void evaluate(Ontology ontology, RuleContext context, RuleEffect effect);
}
