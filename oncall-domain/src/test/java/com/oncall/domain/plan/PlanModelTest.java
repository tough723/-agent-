package com.oncall.domain.plan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 计划领域模型的构造期不变量。纯领域测试，不碰数据库也不碰工具策略。
 */
@DisplayName("Plan / PlanStep / BasisRef：依据可追溯与序号连续性")
class PlanModelTest {

    private static PlanStep read(int seq) {
        return new PlanStep(seq, "k8s_get_pods", "{}",
                List.of(BasisRef.alertRule("rule-high-latency")));
    }

    private static PlanStep write(int seq, BasisRef... refs) {
        return new PlanStep(seq, "scale_replicas", "{\"replicas\":3}", List.of(refs));
    }

    @Test
    @DisplayName("hasTrustedBasis：告警规则与 Runbook 可信，日志文本与工具输出不可信")
    void trustedBasisFollowsTheDesignRule() {
        assertThat(write(1, BasisRef.alertRule("r1")).hasTrustedBasis()).isTrue();
        assertThat(write(1, BasisRef.runbook("rb1")).hasTrustedBasis()).isTrue();
        assertThat(write(1, BasisRef.logText("app.log:42")).hasTrustedBasis())
                .as("日志文本是注入攻击的载体，不得作为高危操作的授权依据")
                .isFalse();
        assertThat(write(1, new BasisRef(BasisType.TOOL_OUTPUT, "step-1"))
                .hasTrustedBasis()).isFalse();
        // 混合：只要有一条可信就够
        assertThat(write(1, BasisRef.logText("app.log:42"), BasisRef.runbook("rb1"))
                .hasTrustedBasis()).isTrue();
    }

    @Test
    @DisplayName("★ 没有依据的步骤无法被构造——不是等到校验时才拦")
    void stepWithoutBasisCannotBeConstructed() {
        assertThatThrownBy(() -> new PlanStep(1, "scale_replicas", "{}", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("没有任何依据");
        assertThatThrownBy(() -> new PlanStep(1, "scale_replicas", "{}", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("★ basisRefs 是不可变副本——调用方事后 add 不能偷偷补一条「可信依据」")
    void basisRefsIsDefensivelyCopied() {
        List<BasisRef> mutable = new ArrayList<>();
        mutable.add(BasisRef.logText("app.log:42"));
        PlanStep step = new PlanStep(1, "scale_replicas", "{}", mutable);
        assertThat(step.hasTrustedBasis()).isFalse();

        mutable.add(BasisRef.runbook("rb-forged"));
        assertThat(step.hasTrustedBasis())
                .as("构造后修改原列表不得影响已构造的步骤")
                .isFalse();
        assertThatThrownBy(() -> step.basisRefs().add(BasisRef.runbook("rb-x")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("★ seq 必须从 1 起连续且有序，否则顺序约束会静默失效")
    void planRequiresContiguousOrderedSeq() {
        assertThatThrownBy(() -> Plan.of(read(1), read(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("连续整数");
        assertThatThrownBy(() -> Plan.of(read(2), read(1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("第 1 个位置的步骤 seq=2");
        assertThatThrownBy(() -> Plan.of(read(0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("从 1 开始");
        assertThat(Plan.of(read(1), read(2), read(3)).size()).isEqualTo(3);
    }

    @Test
    @DisplayName("★ 空计划被拒——「无需处置」必须是 Reporter 的显式结论")
    void emptyPlanIsRejected() {
        assertThatThrownBy(() -> new Plan(List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无需处置");
    }

    @Test
    @DisplayName("step(seq) 按序号取步，越界即拒")
    void stepLookupBySeq() {
        Plan p = Plan.of(read(1), read(2));
        assertThat(p.step(2).action()).isEqualTo("k8s_get_pods");
        assertThatThrownBy(() -> p.step(3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("超出计划范围");
    }

    @Test
    @DisplayName("列宽与空白一律拒绝而不是截断")
    void rejectsBlankAndOversized() {
        assertThatThrownBy(() -> new PlanStep(1, "  ", "{}", List.of(BasisRef.runbook("r"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("空白");
        assertThatThrownBy(() -> new PlanStep(1, "a".repeat(192), "{}",
                List.of(BasisRef.runbook("r"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("绝不截断");
        assertThatThrownBy(() -> new BasisRef(BasisType.RUNBOOK, "  "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("可追溯");
        assertThatThrownBy(() -> new BasisRef(BasisType.RUNBOOK, "r".repeat(192)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("绝不截断");
    }
}
