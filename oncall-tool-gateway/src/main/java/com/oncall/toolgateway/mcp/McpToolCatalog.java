package com.oncall.toolgateway.mcp;

import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * MCP 工具目录端口：某个 server 当前提供哪些工具。
 *
 * <p><b>为什么要有这个端口而不是直接用 Spring AI 的 MCP client</b>：
 * MCP 工具是运行期发现的，而纳管决策必须是可测试的。
 * 有了端口，"server 突然多出一个危险工具"这种场景可以在单元测试里复现，
 * 不需要真的起一个 MCP server。
 *
 * <p>真实实现会包一层 Spring AI 的 MCP client；
 * 同时必须把 {@code spring.ai.mcp.client.toolcallback.enabled} 设为 {@code false}
 * （<b>默认是 true</b>），否则框架会自己把工具注册给模型，
 * 整个纳管流程被绕过——那是不变量 I14 要堵的洞。
 */
public interface McpToolCatalog {

    /**
     * 列出该 server 当前提供的原始工具。
     *
     * <p>实现可以抛异常（server 不可达、握手失败）。
     * 调用方必须容忍：一个 MCP server 挂掉不应该让整个 Agent 起不来。
     */
    List<ToolCallback> fetch(String server);
}
