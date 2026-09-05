package com.oncall.archtest.fixture;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * <b>故意违规的 fixture</b>：一个绕过 {@code GuardedToolCallback} 的 ToolCallback 实现。
 *
 * <p>它的唯一用途是证明 F9 规则真的会失败。
 * <b>一条从没红过的规则等于没写</b>——它可能因为包名写错、扫描路径写错、
 * 或者 ArchUnit 版本行为变化而静默通过，而你以为它在守着。
 *
 * <p>这个类必须放在 {@code com.oncall.archtest..} 下：
 * 生产扫描只看 {@code com.oncall.domain / config / toolgateway / ontology}，
 * 否则 F9 的主检查会把这个 fixture 也算成违规。
 *
 * <p><b>不要把它移到 main 源码里</b>——那样它就成了真实的绕过路径。
 */
public class UnguardedToolCallbackFixture implements ToolCallback {

    @Override
    public ToolDefinition getToolDefinition() {
        return null;
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return null;
    }

    @Override
    public String call(String toolInput) {
        throw new UnsupportedOperationException("fixture，不应被调用");
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        throw new UnsupportedOperationException("fixture，不应被调用");
    }
}
