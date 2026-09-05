package com.oncall.domain.tool;

/**
 * 工具来源。
 *
 * <p>【为什么必须有】原方案的安全实现是"给工具打 {@code @RiskLevel} 注解"，
 * 但 MCP 远端工具是运行期动态发现的 {@code ToolCallback}，不是项目里的 Java 类，打不了注解——
 * 于是 MCP 工具会绕过整套风险分级。策略必须按 (来源 + 工具名) 寻址，且默认拒绝。
 */
public enum ToolSource {
    /** 本项目内 {@code @Tool} 注解的方法。 */
    LOCAL,
    /** 远端 MCP Server 提供，工具名统一加 {@code mcp:<server>:} 前缀。 */
    MCP
}
