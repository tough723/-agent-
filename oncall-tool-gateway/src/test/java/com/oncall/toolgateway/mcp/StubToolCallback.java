package com.oncall.toolgateway.mcp;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * 测试用的假 MCP 工具。
 *
 * <p>它实现了 {@code ToolCallback} 但<b>不会</b>被 ArchUnit 的 F9 规则拦下，
 * 因为它在测试源码里，而 F9 只扫生产包。
 * 这恰好说明 F9 的扫描范围是对的：测试替身必须能存在，
 * 否则连"绕过守卫"这种场景都没法测。
 */
final class StubToolCallback implements ToolCallback {

    private final String name;
    private final String result;
    int calls;

    private StubToolCallback(String name, String result) {
        this.name = name;
        this.result = result;
    }

    static StubToolCallback named(String name) {
        return new StubToolCallback(name, "ok:" + name);
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return ToolDefinition.builder()
                .name(name)
                .description("假 MCP 工具 " + name)
                .inputSchema("{\"type\":\"object\"}")
                .build();
    }

    @Override
    public String call(String toolInput) {
        calls++;
        return result;
    }
}
