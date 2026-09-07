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
 *   <li>工具风险等级<b>不是</b> {@link RiskLevel#HIGH}（HIGH 永远要审批；
 *       {@link RiskLevel#READ_ONLY} 与 {@link RiskLevel#LOW} 都可以，
 *       LOW 的「二次确认」由第 ④ 条的白名单承担）</li>
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
        // ★ 排除 HIGH，而不是「要求恰好等于 LOW」。
        //   原写法是 policy.risk() != RiskLevel.LOW，它把 READ_ONLY 也拒了——
        //   而 RiskLevel 的注释明写 READ_ONLY 是「只读：Agent 可直接调用」，
        //   LOW 反而是「可调用，需二次确认」。原写法与两个枚举值的注释都相反，
        //   也与本类 javadoc 括号里那句「HIGH 永远要审批」的原意不符。
        //   LOW 的「二次确认」由第 ④ 条（自动执行白名单）承担：
        //   把一个工具放进那个白名单本身就是一次显式的人工决定。
        if (policy.risk() == RiskLevel.HIGH) {
            return false;
        }
        return inAutoWhitelist;
    }

    /** 便捷重载：不在白名单内。 */
    public static boolean canAutoExecute(AutonomyLevel level, AlertSeverity severity, ToolPolicy policy) {
        return canAutoExecute(level, severity, policy, false);
    }
}
