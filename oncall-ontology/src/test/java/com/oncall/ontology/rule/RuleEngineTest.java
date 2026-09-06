package com.oncall.ontology.rule;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.ontology.Ontology;
import com.oncall.ontology.OntologyFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 规则求值测试。
 *
 * <p>核心断言是<b>效果取更严格的那一档，而不是叠加或覆盖</b>——
 * 叠加会得到「四人审批」这种荒谬结论，覆盖会丢掉另一条规则的约束。
 *
 * <p>R4 全部用固定时钟，不读系统时间：
 * 这条规则的全部逻辑就是「过了多久」，
 * 时钟不可注入就只能把时间戳设成很久以前来间接触发，那测的是常量不是逻辑。
 */
class RuleEngineTest {

    private static final long NOW = 1_800_000_000_000L;
    private static final long DAY = 24L * 60 * 60 * 1000;

    private final Ontology ontology = new Ontology(OntologyFixture.store());

    /** 用固定时钟构造引擎，保证 R4 可重复。 */
    private RuleEngine engine() {
        return new RuleEngine(List.of(
                new IrreversibleNeedsTwoApprovals(),
                new CriticalServiceNeedsTwoApprovals(),
                new ResourceAlertOnCriticalServiceCapsAutonomy(),
                new StaleRunbookMustBeFlagged(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC))
        ));
    }

    private RuleContext ctx(String serviceId, String alertId, RiskLevel risk,
                            boolean irreversible, long runbookUpdated) {
        return new RuleContext(serviceId, alertId, "op:restart", risk, irreversible, runbookUpdated);
    }

    // ------------------------------------------------------------ R1 不可逆

    @Test
    @DisplayName("R1：不可逆操作 ⇒ 两人审批")
    void r1IrreversibleRequiresTwo() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, true, RuleContext.NO_RUNBOOK));
        assertEquals(2, e.minApprovers());
        assertTrue(e.firedRuleIds().contains(IrreversibleNeedsTwoApprovals.ID));
    }

    @Test
    @DisplayName("R1 反例：可逆操作只要一人")
    void r1ReversibleNeedsOne() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, false, RuleContext.NO_RUNBOOK));
        assertEquals(1, e.minApprovers());
        assertFalse(e.hasEffect());
    }

    // ------------------------------------------------------------ R2 关键服务

    @Test
    @DisplayName("R2：CRITICAL 服务 + 高危 ⇒ 两人审批")
    void r2CriticalServiceRequiresTwo() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:cpu", RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK));
        assertEquals(2, e.minApprovers());
        assertTrue(e.firedRuleIds().contains(CriticalServiceNeedsTwoApprovals.ID));
    }

    @Test
    @DisplayName("R2 反例：CRITICAL 服务但操作只读 ⇒ 不需要两人")
    void r2LowRiskDoesNotTrigger() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:cpu", RiskLevel.READ_ONLY, false, RuleContext.NO_RUNBOOK));
        assertEquals(1, e.minApprovers());
    }

    @Test
    @DisplayName("R2 反例：普通服务 + 高危 ⇒ 不需要两人（关键度是属性，不是所有服务都核心）")
    void r2NormalServiceDoesNotTrigger() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK));
        assertEquals(1, e.minApprovers());
    }

    @Test
    @DisplayName("R2：关键度不从父类继承 —— 标错一个父节点不该连带几十个子服务")
    void r2CriticalityDoesNotInherit() {
        // service:payment-batch 的父节点是 domain:payment（NORMAL），
        // 而 payment-gateway 自己是 CRITICAL：两者的判定必须互不影响
        assertEquals(1, engine().evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK))
                .minApprovers());
        assertEquals(2, engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:cpu", RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK))
                .minApprovers());
    }

    @Test
    @DisplayName("风险等级缺失时按 HIGH 处理 —— MCP 运行时拉取的工具没有注解")
    void missingRiskLevelDefaultsToStrictest() {
        RuleContext c = new RuleContext("service:payment-gateway", null, null,
                null, false, RuleContext.NO_RUNBOOK);
        assertEquals(RiskLevel.HIGH, c.riskLevel());
        assertTrue(c.isHighRisk());
        assertEquals(2, engine().evaluate(ontology, c).minApprovers());
    }

    // ------------------------------------------------------------ R3 放权上限

    @Test
    @DisplayName("R3：CRITICAL 服务 + 资源类告警 ⇒ 放权上限 S1")
    void r3CapsAutonomy() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:disk", RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK));
        assertEquals("S1", e.maxAutonomy());
        assertTrue(e.firedRuleIds().contains(ResourceAlertOnCriticalServiceCapsAutonomy.ID));
    }

    @Test
    @DisplayName("R3 用到 is-a 推理：磁盘告警是资源类告警的子孙")
    void r3UsesIsaReasoning() {
        RuleEffect disk = engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:disk", RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK));
        RuleEffect cpu = engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:cpu", RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK));
        assertEquals("S1", disk.maxAutonomy());
        assertEquals("S1", cpu.maxAutonomy());
    }

    @Test
    @DisplayName("R3 反例：业务告警不是资源类 ⇒ 不限制放权")
    void r3BusinessAlertDoesNotCap() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:biz", RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK));
        assertNull(e.maxAutonomy());
    }

    @Test
    @DisplayName("R3 反例：普通服务的资源类告警 ⇒ 不限制放权")
    void r3NormalServiceDoesNotCap() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-batch", "alert:disk", RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK));
        assertNull(e.maxAutonomy());
    }

    @Test
    @DisplayName("R3：告警概念缺失时不触发 —— 缺数据不猜")
    void r3WithoutAlertConcept() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-gateway", null, RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK));
        assertNull(e.maxAutonomy());
    }

    // ------------------------------------------------------------ R4 过期知识

    @Test
    @DisplayName("R4：Runbook 超 180 天 ⇒ 必须声明可能过期")
    void r4FlagsStaleRunbook() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:cpu", RiskLevel.LOW, false, NOW - 181 * DAY));
        assertTrue(e.firedRuleIds().contains(StaleRunbookMustBeFlagged.ID));
        assertEquals(1, e.warnings().size());
        assertTrue(e.warnings().get(0).contains("180"), e.warnings().get(0));
    }

    @Test
    @DisplayName("R4 反例：新鲜的 Runbook 不告警")
    void r4FreshRunbookIsSilent() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:cpu", RiskLevel.LOW, false, NOW - 30 * DAY));
        assertFalse(e.firedRuleIds().contains(StaleRunbookMustBeFlagged.ID));
        assertTrue(e.warnings().isEmpty());
    }

    @Test
    @DisplayName("R4 反例：没有引用 Runbook 时不告警（过期检查的前提是有引用）")
    void r4NoRunbookIsSilent() {
        // 用普通服务 + 可逆 + 低危，确保没有任何其他规则触发，
        // 否则断言的是别的规则而不是 R4
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, false, RuleContext.NO_RUNBOOK));
        assertFalse(e.hasEffect());
    }

    // ------------------------------------------------------------ 效果合并

    @Test
    @DisplayName("多条规则同时触发时取更严格的一档，不是叠加")
    void effectsTakeStrictestNotSum() {
        // R1 与 R2 同时触发，都要求 2 人 —— 结果必须是 2，不是 4
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:disk", RiskLevel.HIGH, true, NOW - 400 * DAY));
        assertEquals(2, e.minApprovers(), "审批人数取最严格值，不能叠加");
        assertEquals("S1", e.maxAutonomy());
        assertEquals(1, e.warnings().size());
        assertEquals(List.of(
                IrreversibleNeedsTwoApprovals.ID,
                CriticalServiceNeedsTwoApprovals.ID,
                ResourceAlertOnCriticalServiceCapsAutonomy.ID,
                StaleRunbookMustBeFlagged.ID), e.firedRuleIds());
    }

    @Test
    @DisplayName("审计能看到「为什么要求两人审批」——触发规则必须落库")
    void firedRulesAreTraceable() {
        // 用业务告警避开 R3，确保只有 R2 触发
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-gateway", "alert:biz", RiskLevel.HIGH, false, RuleContext.NO_RUNBOOK));
        assertEquals(List.of(CriticalServiceNeedsTwoApprovals.ID), e.firedRuleIds());
    }

    @Test
    @DisplayName("单条规则抛异常不会让其余约束一起失效")
    void failingRuleDoesNotDisableOthers() {
        OntologyRule broken = new OntologyRule() {
            @Override
            public String id() {
                return "R-BROKEN";
            }

            @Override
            public String description() {
                return "故意抛异常";
            }

            @Override
            public void evaluate(Ontology o, RuleContext c, RuleEffect e) {
                throw new IllegalStateException("boom");
            }
        };
        RuleEngine engine = new RuleEngine(List.of(
                broken, new IrreversibleNeedsTwoApprovals()));
        RuleEffect e = engine.evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, true, RuleContext.NO_RUNBOOK));
        assertEquals(2, e.minApprovers(), "坏规则不能连带把 R1 的约束弄丢");
        assertEquals(1, e.warnings().size());
        assertTrue(e.warnings().get(0).contains("R-BROKEN"), e.warnings().get(0));
    }

    @Test
    @DisplayName("默认引擎加载 4 条规则")
    void defaultEngineHasFourRules() {
        assertEquals(4, new RuleEngine().size());
    }

    @Test
    @DisplayName("规则集合不可变：放权约束走变更审批，不走热更新")
    void ruleListIsImmutable() {
        java.util.List<OntologyRule> mutable = new java.util.ArrayList<>();
        mutable.add(new IrreversibleNeedsTwoApprovals());
        RuleEngine engine = new RuleEngine(mutable);
        mutable.add(new CriticalServiceNeedsTwoApprovals());
        assertEquals(1, engine.size(), "构造后外部改动不应影响引擎");
    }

    // -------------------------------------------------- 求值失败 = 降级，不是放过

    /** 一条本该收紧约束、却抛异常的规则。 */
    private static OntologyRule brokenTighteningRule(String id) {
        return new OntologyRule() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return "本该收紧约束，但求值失败";
            }

            @Override
            public void evaluate(Ontology o, RuleContext c, RuleEffect e) {
                throw new IllegalStateException("boom");
            }
        };
    }

    @Test
    @DisplayName("★ 收紧型规则求值失败 ⇒ 仍按两人审批，而不是退回一人")
    void failingTighteningRuleStillRequiresTwoApprovers() {
        // 引擎里只有这一条规则，且它抛异常。
        // 旧实现下 minApprovers 会停在 1 —— 也就是「规则没跑成 ⇒ 约束消失」，
        // 而调用方从数值上完全看不出区别。这条断言就是为了钉住那个差异。
        RuleEngine engine = new RuleEngine(List.of(brokenTighteningRule("R-BROKEN")));
        RuleEffect e = engine.evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, false, RuleContext.NO_RUNBOOK));

        assertEquals(RuleEffect.CONSERVATIVE_MIN_APPROVERS, e.minApprovers(),
                "规则求值失败不能等于约束消失");
    }

    @Test
    @DisplayName("★ 求值失败 ⇒ 放权上限压到 S1，而不是「无限制」")
    void failingRuleCapsAutonomy() {
        RuleEngine engine = new RuleEngine(List.of(brokenTighteningRule("R-BROKEN")));
        RuleEffect e = engine.evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, false, RuleContext.NO_RUNBOOK));

        // 旧实现下 maxAutonomy 是 null，语义正是「无限制」——最危险的误读。
        assertEquals(RuleEffect.CONSERVATIVE_AUTONOMY_CAP, e.maxAutonomy());
    }

    @Test
    @DisplayName("★ 只有降级、没有规则触发时，hasEffect 仍为 true")
    void degradedAloneCountsAsEffect() {
        RuleEngine engine = new RuleEngine(List.of(brokenTighteningRule("R-BROKEN")));
        RuleEffect e = engine.evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, false, RuleContext.NO_RUNBOOK));

        assertTrue(e.firedRuleIds().isEmpty(), "没有任何规则成功触发");
        assertTrue(e.hasEffect(),
                "旧实现返回 false，调用方会读成「没有约束，可以自动执行」");
    }

    @Test
    @DisplayName("degraded() 与 degradedRuleIds() 暴露是哪条规则没跑成")
    void degradedStateIsInspectable() {
        RuleEngine engine = new RuleEngine(List.of(
                brokenTighteningRule("R-BROKEN-A"), brokenTighteningRule("R-BROKEN-B")));
        RuleEffect e = engine.evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, false, RuleContext.NO_RUNBOOK));

        assertTrue(e.degraded());
        assertEquals(List.of("R-BROKEN-A", "R-BROKEN-B"), e.degradedRuleIds());
    }

    @Test
    @DisplayName("全部规则正常时不降级——降级不能被误报")
    void healthyEvaluationIsNotDegraded() {
        RuleEffect e = engine().evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, false, RuleContext.NO_RUNBOOK));

        assertFalse(e.degraded());
        assertTrue(e.degradedRuleIds().isEmpty());
    }

    @Test
    @DisplayName("保守下限是「取更严」而不是叠加：与已触发的两人审批合并后仍是 2")
    void conservativeFloorDoesNotStack() {
        RuleEngine engine = new RuleEngine(List.of(
                brokenTighteningRule("R-BROKEN"), new IrreversibleNeedsTwoApprovals()));
        RuleEffect e = engine.evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, true, RuleContext.NO_RUNBOOK));

        assertEquals(2, e.minApprovers(), "叠加会得到 4 人审批这种荒谬结论");
        assertEquals(1, e.warnings().size(), "两条失败规则也只汇总成一条警告");
    }

    @Test
    @DisplayName("降级警告里写明已按保守下限处理——不是让人以为「约束未生效」")
    void warningStatesTheConservativeFallback() {
        RuleEngine engine = new RuleEngine(List.of(brokenTighteningRule("R-BROKEN")));
        RuleEffect e = engine.evaluate(ontology,
                ctx("service:payment-batch", "alert:cpu", RiskLevel.LOW, false, RuleContext.NO_RUNBOOK));

        assertEquals(1, e.warnings().size());
        String w = e.warnings().get(0);
        assertTrue(w.contains("R-BROKEN"), w);
        assertTrue(w.contains("保守下限"), w);
    }
}
