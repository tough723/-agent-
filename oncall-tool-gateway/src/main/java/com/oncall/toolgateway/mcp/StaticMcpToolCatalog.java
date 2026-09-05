package com.oncall.toolgateway.mcp;

import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 静态目录：工具清单在启动时给定，之后不变。
 *
 * <p><b>它的定位不是"玩具实现"</b>：在 S0 影子模式下，MCP server 的清单
 * 本来就应该是<b>人工确认过并冻结</b>的，运行期自动发现新工具恰恰是要防的事。
 * 所以这个实现在 S0 阶段就是正确实现，不只是测试替身。
 *
 * <p>要接入真实 MCP client 时，另写一个实现即可——
 * {@link McpToolRegistrar} 只依赖 {@link McpToolCatalog} 端口。
 */
public final class StaticMcpToolCatalog implements McpToolCatalog {

    private final Map<String, List<ToolCallback>> byServer = new LinkedHashMap<>();
    private final Map<String, RuntimeException> failures = new LinkedHashMap<>();

    /** 登记一个 server 提供的工具。 */
    public StaticMcpToolCatalog withServer(String server, ToolCallback... tools) {
        byServer.computeIfAbsent(server, k -> new ArrayList<>()).addAll(List.of(tools));
        return this;
    }

    /**
     * 让某个 server 在拉取时失败。
     *
     * <p>用于验证"一个 MCP server 挂掉不应该让整个 Agent 起不来"这条性质——
     * 这类失效路径不刻意构造就永远不会被测到。
     */
    public StaticMcpToolCatalog withFailure(String server, RuntimeException error) {
        failures.put(server, error);
        return this;
    }

    @Override
    public List<ToolCallback> fetch(String server) {
        RuntimeException failure = failures.get(server);
        if (failure != null) {
            throw failure;
        }
        return List.copyOf(byServer.getOrDefault(server, List.of()));
    }

    /** 已登记的 server 名，供启动自检与指标使用。 */
    public List<String> servers() {
        return List.copyOf(byServer.keySet());
    }
}
