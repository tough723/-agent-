package com.oncall.domain.tool;

import java.time.Duration;

/**
 * 单个工具的安全策略。
 *
 * <p>与代码解耦：从配置中心 / DB 加载，新增工具不需要改代码重新发布。
 * 原方案把风险等级写成注解，等于把策略硬编码进字节码，且对 MCP 工具无效。
 *
 * @param toolName             本地工具用原名；MCP 工具用 {@code mcp:<server>:<tool>}
 * @param source               来源
 * @param risk                 风险等级
 * @param requiresApproval     是否需要人工审批
 * @param approvalTimeout      审批超时——到点自动拒绝并升级，不允许无限等待
 * @param requiresDualApproval 是否需双人复核（建议 P0 故障的高危操作强制）
 * @param argsJsonSchema       参数级 JSON Schema 校验，可为 null
 */
public record ToolPolicy(
        String toolName,
        ToolSource source,
        RiskLevel risk,
        boolean requiresApproval,
        Duration approvalTimeout,
        boolean requiresDualApproval,
        String argsJsonSchema) {

    public static ToolPolicy readOnly(String toolName) {
        return new ToolPolicy(toolName, ToolSource.LOCAL, RiskLevel.READ_ONLY,
                false, Duration.ZERO, false, null);
    }

    public static ToolPolicy highRisk(String toolName, Duration approvalTimeout) {
        return new ToolPolicy(toolName, ToolSource.LOCAL, RiskLevel.HIGH,
                true, approvalTimeout, false, null);
    }

    public boolean isWriteOperation() {
        return risk != RiskLevel.READ_ONLY;
    }
}
