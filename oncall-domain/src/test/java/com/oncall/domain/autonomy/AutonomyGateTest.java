package com.oncall.domain.autonomy;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.tool.ToolSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 放权判定测试（渐进式落地四阶段）。
 *
 * <p>核心不变量：<b>S0-S2 绝不允许自动写操作</b>，且 P0/P1 与 HIGH 风险永远要人工。
 */
class AutonomyGateTest {

    private static final ToolPolicy LOW = new ToolPolicy(
            "scale_replicas", ToolSource.LOCAL, RiskLevel.LOW, false, Duration.ZERO, false, null);
    private static final ToolPolicy HIGH = new ToolPolicy(
            "rollback", ToolSource.LOCAL, RiskLevel.HIGH, true, Duration.ofMinutes(15), true, null);
    /**
     * 只读工具。这个夹具<b>曾经不存在</b>，而那正是下面那个缺陷能活到今天的原因：
     * 测试只用了 LOW 与 HIGH 两个夹具，于是「READ_ONLY 被拒」这件事没有任何断言覆盖。
     */
    private static final ToolPolicy READ_ONLY = new ToolPolicy(
            "k8s_get_pods", ToolSource.LOCAL, RiskLevel.READ_ONLY, false, Duration.ZERO, false, null);

    @Test
    @DisplayName("核心不变量：S0-S2 一律不允许自动执行")
    void shadowSuggestAssistNeverAutoExecute() {
        for (AutonomyLevel level : new AutonomyLevel[]{
                AutonomyLevel.SHADOW, AutonomyLevel.SUGGEST, AutonomyLevel.ASSIST}) {
            assertThat(AutonomyGate.canAutoExecute(level, AlertSeverity.P2, LOW, true))
                    .as("level=%s 不应允许自动执行", level)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("S3 且四个条件全满足才允许自动执行")
    void boundedAutoAllowsWhenAllConditionsMet() {
        assertThat(AutonomyGate.canAutoExecute(AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P2, LOW, true))
                .isTrue();
        assertThat(AutonomyGate.canAutoExecute(AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P3, LOW, true))
                .isTrue();
    }

    @Test
    @DisplayName("P0/P1 高危故障：即使 S3 + 白名单也不允许自动执行")
    void criticalAlertsNeverAutoExecute() {
        assertThat(AutonomyGate.canAutoExecute(AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P0, LOW, true)).isFalse();
        assertThat(AutonomyGate.canAutoExecute(AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P1, LOW, true)).isFalse();
    }

    @Test
    @DisplayName("★ READ_ONLY 必须允许自动执行——它是「Agent 可直接调用」的那一级")
    void readOnlyIsAllowedToAutoExecute() {
        // RiskLevel 的注释：READ_ONLY =「只读：Agent 可直接调用」。
        // 原实现写的是 policy.risk() != RiskLevel.LOW → 拒绝，于是最安全的
        // 只读探查反而被拦下，而注释说「需二次确认」的 LOW 却放行——
        // 与两个枚举值的注释都相反。这条断言就是钉住那次修复的。
        assertThat(AutonomyGate.canAutoExecute(
                AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P2, READ_ONLY, true)).isTrue();
        assertThat(AutonomyGate.canAutoExecute(
                AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P3, READ_ONLY, true)).isTrue();
    }

    @Test
    @DisplayName("★ 但 READ_ONLY 也不能越过其它三个条件")
    void readOnlyStillRespectsTheOtherThreeConditions() {
        // 放权级别不够
        assertThat(AutonomyGate.canAutoExecute(
                AutonomyLevel.SHADOW, AlertSeverity.P2, READ_ONLY, true)).isFalse();
        assertThat(AutonomyGate.canAutoExecute(
                AutonomyLevel.ASSIST, AlertSeverity.P2, READ_ONLY, true)).isFalse();
        // P0/P1 高危故障：连只读也不自动执行——此时人来主导，AI 不该自己乱查
        assertThat(AutonomyGate.canAutoExecute(
                AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P0, READ_ONLY, true)).isFalse();
        assertThat(AutonomyGate.canAutoExecute(
                AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P1, READ_ONLY, true)).isFalse();
        // 不在自动执行白名单内
        assertThat(AutonomyGate.canAutoExecute(
                AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P2, READ_ONLY, false)).isFalse();
    }

    @Test
    @DisplayName("★ 三个风险等级对自动执行的态度必须与 RiskLevel 的注释一致")
    void riskLevelsBehaveAsDocumented() {
        // 把三级并排列出来，避免再次出现「注释说 A、代码做 B」而无人察觉。
        assertThat(AutonomyGate.canAutoExecute(
                AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P2, READ_ONLY, true))
                .as("READ_ONLY 注释：只读，Agent 可直接调用").isTrue();
        assertThat(AutonomyGate.canAutoExecute(
                AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P2, LOW, true))
                .as("LOW 注释：可调用，需二次确认——白名单即那次确认").isTrue();
        assertThat(AutonomyGate.canAutoExecute(
                AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P2, HIGH, true))
                .as("HIGH 注释：必须人工审批才能落地").isFalse();
    }

    @Test
    @DisplayName("HIGH 风险工具：即使在 S3 也必须审批")
    void highRiskAlwaysNeedsApproval() {
        assertThat(AutonomyGate.canAutoExecute(AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P2, HIGH, true)).isFalse();
    }

    @Test
    @DisplayName("不在自动白名单内不允许执行")
    void notInWhitelistIsDenied() {
        assertThat(AutonomyGate.canAutoExecute(AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P2, LOW, false)).isFalse();
    }

    @Test
    @DisplayName("便捷重载默认视为不在白名单")
    void convenienceOverloadDefaultsToNotWhitelisted() {
        assertThat(AutonomyGate.canAutoExecute(AutonomyLevel.BOUNDED_AUTO, AlertSeverity.P2, LOW)).isFalse();
    }

    @Test
    @DisplayName("S0 影子模式对人不可见；S1 起可见")
    void visibilityByLevel() {
        assertThat(AutonomyLevel.SHADOW.isVisibleToHuman()).isFalse();
        assertThat(AutonomyLevel.SUGGEST.isVisibleToHuman()).isTrue();
        assertThat(AutonomyLevel.ASSIST.isVisibleToHuman()).isTrue();
        assertThat(AutonomyLevel.BOUNDED_AUTO.isVisibleToHuman()).isTrue();
    }

    @Test
    @DisplayName("只有 S3 允许自动执行")
    void allowsAutoExecutionFlag() {
        assertThat(AutonomyLevel.SHADOW.allowsAutoExecution()).isFalse();
        assertThat(AutonomyLevel.SUGGEST.allowsAutoExecution()).isFalse();
        assertThat(AutonomyLevel.ASSIST.allowsAutoExecution()).isFalse();
        assertThat(AutonomyLevel.BOUNDED_AUTO.allowsAutoExecution()).isTrue();
    }
}
