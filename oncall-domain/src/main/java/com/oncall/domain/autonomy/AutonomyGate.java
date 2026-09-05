package com.oncall.domain.autonomy;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolPolicy;

/**
 * 放权判定（Strategy 模式的判定点）。
 *
 * <p>四个条件必须同时满足才允许自动执行：
 * <ol>
 *   <li>放权级别为 {@link AutonomyLevel#BOUNDED_AUTO}</li>
 *   <li>告警不是 P0/P1（高危故障一律人工）</li>
 *   <li>工具风险等级为 {@link RiskLevel#LOW}（HIGH 永远要审批）</li>
 *   <li>工具在自动执行白名单内</li>
 * </ol>
 *
 * <p>注意：本类<b>不</b>判断 kill switch——那是运行时的正交维度，
 * 由调用方先过 {@code KillSwitch.assertAllowed()} 再调这里。
 */
public final class AutonomyGate {

    private AutonomyGate() {}

    public static boolean canAutoExecute(AutonomyLevel level, AlertSeverity severity,
                                         ToolPolicy policy, boolean inAutoWhitelist) {
        if (!level.allowsAutoExecution()) {
            return false;
        }
        if (severity.isCritical()) {
            return false;
        }
        if (policy.risk() != RiskLevel.LOW) {
            return false;
        }
        return inAutoWhitelist;
    }

    /** 便捷重载：不在白名单内。 */
    public static boolean canAutoExecute(AutonomyLevel level, AlertSeverity severity, ToolPolicy policy) {
        return canAutoExecute(level, severity, policy, false);
    }
}
