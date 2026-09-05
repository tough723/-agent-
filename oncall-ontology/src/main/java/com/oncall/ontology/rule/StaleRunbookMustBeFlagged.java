package com.oncall.ontology.rule;

import com.oncall.ontology.Ontology;
import java.time.Clock;

/**
 * R4：引用超过 180 天未更新的 Runbook ⇒ 必须在回答里声明「这份资料可能过期」。
 *
 * <p><b>这不是本体问题，是一个时间戳判断</b>。放在规则层是因为它的**性质**
 * 与其他三条相同：从领域知识推出一条约束。
 *
 * <p><b>为什么要有这条</b>：过期的知识比没有知识更危险——
 * 系统会自信地引用一份两年前的手册，而运维照着执行。
 * 对策不是不做知识库，是让系统**主动声明不确定性**。
 * 这是 RAG 系统最容易被忽略的一个失效模式：
 * 检索到了东西，但没人检查那东西还是不是对的。
 */
public final class StaleRunbookMustBeFlagged implements OntologyRule {

    public static final String ID = "R4";

    /** 180 天，毫秒。 */
    public static final long STALE_AFTER_MILLIS = 180L * 24 * 60 * 60 * 1000;

    /**
     * 时钟注入，不直接调 {@code System.currentTimeMillis()}。
     *
     * <p>这条规则的全部逻辑就是「过了多久」，
     * 时钟不可注入的话这条规则**在测试里没法验证**——
     * 只能靠把时间戳设成很久以前来间接触发，那测的是常量不是逻辑。
     */
    private final Clock clock;

    public StaleRunbookMustBeFlagged() {
        this(Clock.systemDefaultZone());
    }

    public StaleRunbookMustBeFlagged(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String description() {
        return "Runbook 超过 180 天未更新 ⇒ 回答必须声明资料可能过期";
    }

    @Override
    public void evaluate(Ontology ontology, RuleContext context, RuleEffect effect) {
        if (!context.hasRunbook()) {
            return;
        }
        if (Ontology.isRunbookStale(context.runbookLastUpdated(), clock.millis(), STALE_AFTER_MILLIS)) {
            effect.warn("引用的 Runbook 已超过 " + (STALE_AFTER_MILLIS / (24L * 60 * 60 * 1000))
                    + " 天未更新，结论可能已过期，执行前请人工确认。");
            effect.markFired(ID);
        }
    }
}
