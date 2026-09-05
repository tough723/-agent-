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
