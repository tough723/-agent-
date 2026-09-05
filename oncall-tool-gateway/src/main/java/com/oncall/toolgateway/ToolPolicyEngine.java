package com.oncall.toolgateway;

import com.oncall.domain.tool.ToolDeniedException;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.tool.ToolSource;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具策略引擎（Chain of Responsibility 的第一环）。
 *
 * <p><b>核心语义：默认拒绝（default-deny）。</b>
 * 不在白名单里的工具一律不放行——包括 MCP Server 运行期偷偷新增的工具。
 * 这是修复"MCP 工具绕过风险分级"的关键：白名单是唯一事实来源，工具自己声称什么不算数。
 *
 * <p>策略从配置中心 / DB 加载，因此新增或调整工具策略不需要改代码重新发布。
 */
public class ToolPolicyEngine {

    /** MCP 工具名的前缀，与 {@code McpToolRegistrar.namespacedName} 必须一致。 */
    public static final String MCP_PREFIX = "mcp:";

    private final Map<String, ToolPolicy> allowList = new ConcurrentHashMap<>();
    private final DenialListener denialListener;

    public ToolPolicyEngine(Collection<ToolPolicy> policies) {
        this(policies, (tool, reason) -> { });
    }

    public ToolPolicyEngine(Collection<ToolPolicy> policies, DenialListener denialListener) {
        policies.forEach(p -> allowList.put(p.toolName(), p));
        this.denialListener = denialListener;
    }

    /**
     * 解析工具策略；未注册即拒绝。
     *
     * @throws ToolDeniedException 工具不在白名单内
     */
    public ToolPolicy resolve(String toolName) {
        ToolPolicy policy = allowList.get(toolName);
        if (policy == null) {
            denialListener.onDenied(toolName, "not in allowlist");
            throw new ToolDeniedException(toolName, "not in allowlist");
        }
        return policy;
    }

    /** 不抛异常的探测版本，供 MCP 纳管时逐个过滤使用。 */
    public Optional<ToolPolicy> find(String toolName) {
        return Optional.ofNullable(allowList.get(toolName));
    }

    public void register(ToolPolicy policy) {
        allowList.put(policy.toolName(), policy);
    }

    public void revoke(String toolName) {
        allowList.remove(toolName);
    }

    public int size() {
        return allowList.size();
    }

    /**
     * 从已注册的 MCP 工具策略反推出"当前被授权连接的 server 集合"。
     *
     * <p><b>为什么是反推而不是单独配一份 server 名单</b>：
     * 两份清单必然会出现互相矛盾的状态——
     * server 在名单里但没有任何工具策略，连上去什么也做不了；
     * 有工具策略但 server 不在名单里，那是永远不会生效的死配置。
     * 两种情况都不会报错，只会让人困惑。
     * 单一事实来源不可能与自己矛盾。
     *
     * <p>语义上也更准确：一个 server 值不值得连接，
     * 取决于它有没有<b>被纳管的工具</b>，而不是它是否存在。
     *
     * @return 按名字排序的 server 集合；没有任何 MCP 策略时为空集
     */
    public Set<String> mcpServers() {
        Set<String> out = new TreeSet<>();
        for (ToolPolicy p : allowList.values()) {
            if (p.source() != ToolSource.MCP) {
                continue;
            }
            String name = p.toolName();
            if (!name.startsWith(MCP_PREFIX)) {
                continue;
            }
            String rest = name.substring(MCP_PREFIX.length());
            int sep = rest.indexOf(':');
            if (sep > 0) {
                out.add(rest.substring(0, sep));
            }
        }
        return out;
    }

    /** 拒绝事件回调，用于审计与告警（未注册工具被调用往往意味着 MCP 工具投毒）。 */
    @FunctionalInterface
    public interface DenialListener {
        void onDenied(String toolName, String reason);
    }
}
