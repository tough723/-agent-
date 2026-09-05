package com.oncall.toolgateway.mcp;

import com.oncall.domain.tool.ToolPolicy;
import org.springframework.ai.tool.ToolCallback;

import java.util.List;

/**
 * 一次纳管的结果：接受了哪些、拒绝了哪些、为什么拒绝。
 *
 * <p><b>被拒绝的工具必须出现在结果里，不能静默丢掉。</b>
 * 静默丢弃的后果是：某个 MCP server 悄悄多提供了一个工具，
 * 系统只是"没用它"，没有任何人知道发生过这件事。
 * 而"server 提供了未纳管的工具"恰恰是工具投毒最典型的信号，必须能被看见。
 *
 * @param accepted 通过纳管的工具
 * @param rejected 被拒绝的工具及原因，用于审计与告警
 * @param catalogError 目录本身取不到时的错误（server 不可达等）；正常时为 {@code null}
 */
public record McpRegistrationResult(
        String server,
        List<ManagedMcpTool> accepted,
        List<Rejected> rejected,
        String catalogError
) {

    /**
     * 一个已纳管的 MCP 工具。
     *
     * @param namespacedName {@code mcp:<server>:<tool>}。<b>这是唯一对外的名字</b>，
     *                       原始名不得再出现在任何面向模型或面向策略的地方
     */
    public record ManagedMcpTool(String namespacedName, ToolCallback raw, ToolPolicy policy) {
    }

    /** 一条拒绝记录。 */
    public record Rejected(String rawName, String reason) {
    }

    public McpRegistrationResult {
        accepted = accepted == null ? List.of() : List.copyOf(accepted);
        rejected = rejected == null ? List.of() : List.copyOf(rejected);
    }

    public static McpRegistrationResult of(String server,
                                           List<ManagedMcpTool> accepted,
                                           List<Rejected> rejected) {
        return new McpRegistrationResult(server, accepted, rejected, null);
    }

    public static McpRegistrationResult unavailable(String server, String error) {
        return new McpRegistrationResult(server, List.of(), List.of(), error);
    }

    public boolean isAvailable() {
        return catalogError == null;
    }

    public int acceptedCount() {
        return accepted.size();
    }

    public int rejectedCount() {
        return rejected.size();
    }
}
