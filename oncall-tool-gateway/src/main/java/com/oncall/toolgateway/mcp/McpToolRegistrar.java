package com.oncall.toolgateway.mcp;

import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.tool.ToolSource;
import com.oncall.toolgateway.ApprovalGate;
import com.oncall.toolgateway.ArgClamper;
import com.oncall.toolgateway.GuardedToolCallback;
import com.oncall.toolgateway.IdempotencyStore;
import com.oncall.toolgateway.KillSwitch;
import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolSource;
import com.oncall.toolgateway.ToolAuditContext;
import com.oncall.toolgateway.ToolAuditEvent;
import com.oncall.toolgateway.ToolAuditLog;
import com.oncall.toolgateway.ToolExecutionLedger;
import com.oncall.toolgateway.ToolPolicyEngine;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * MCP 工具的显式纳管器 —— 不变量 I14 的落地实现。
 *
 * <h2>它堵的是什么洞</h2>
 *
 * 原方案的安全实现是"给工具方法打 {@code @RiskLevel} 注解"。但 MCP 工具是
 * <b>运行期从远端 server 发现的对象</b>，不是本项目里的 Java 类，打不了注解。
 * 于是 MCP 工具会绕过整套风险分级——这是原始方案里的 P0 问题。
 *
 * <p>更糟的是 Spring AI 的 MCP client 默认会<b>自动</b>把发现的工具注册给模型
 * （{@code spring.ai.mcp.client.toolcallback.enabled} 默认 {@code true}）。
 * 也就是说什么都不做的情况下，一个远端 server 可以让模型直接获得任意工具。
 *
 * <h2>它怎么堵</h2>
 *
 * <ol>
 *   <li>关掉自动注册（配置项 {@code mcp.toolcallback-enabled=false}，
 *       且该键是 {@code BACKEND_ONLY}，前端根本看不到）；</li>
 *   <li>由本类<b>显式</b>拉取工具清单，逐个到 {@link ToolPolicyEngine} 的白名单里查；</li>
 *   <li>查不到的<b>拒绝并记录</b>，不静默丢弃；</li>
 *   <li>查到的统一改名成 {@code mcp:<server>:<tool>} 后交给
 *       {@link GuardedToolCallback}，走完整七道关卡。</li>
 * </ol>
 *
 * <h2>为什么必须加前缀</h2>
 *
 * 两个不同的 server 完全可能提供同名工具（比如都有 {@code restart}）。
 * 不加前缀时，纳管了 A 的 {@code restart} 就等于纳管了 B 的——
 * 这是权限提升，不是命名冲突。前缀让"哪个 server 的哪个工具"成为可寻址的事实。
 *
 * <h2>为什么改名不放在一个单独的装饰器里</h2>
 *
 * 见 {@link GuardedToolCallback} 里 {@code effectiveName()} 的注释：
 * 模型看到的名字与策略判定用的名字必须证明是同一个值。
 * 拆成两层装饰器就多一处包装顺序出错的机会，而 F9 规则的存在就是为了不留这种机会。
 */
public final class McpToolRegistrar {

    /** 合法的 server / 工具名字符集。刻意不含 {@code :} 与空白。 */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]+");

    private final McpToolCatalog catalog;
    private final ToolPolicyEngine policyEngine;
    private final KillSwitch killSwitch;
    private final ApprovalGate approvalGate;
    private final ToolAuditLog auditLog;
    private final IdempotencyStore idempotencyStore;
    private final ToolExecutionLedger ledger;
    private final ArgClamper argClamper;
    /**
     * 注册期审计上下文。
     *
     * <p>纳管发生在任何 run 之前，所以它有自己的 trace——
     * 借用某个 run 的 trace 会把「启动时这个 server 挂了」
     * 记到一次毫不相干的排查名下。
     */
    private final ToolAuditContext registrationContext;

    public McpToolRegistrar(McpToolCatalog catalog,
                            ToolPolicyEngine policyEngine,
                            KillSwitch killSwitch,
                            ApprovalGate approvalGate,
                            ToolAuditLog auditLog,
                            IdempotencyStore idempotencyStore,
                            ToolExecutionLedger ledger,
                            ArgClamper argClamper,
                            ToolAuditContext registrationContext) {
        if (catalog == null || policyEngine == null || killSwitch == null
                || approvalGate == null || auditLog == null || idempotencyStore == null
                || ledger == null) {
            throw new IllegalArgumentException("McpToolRegistrar 的协作者不能为 null");
        }
        if (registrationContext == null) {
            throw new IllegalArgumentException(
                    "registrationContext 不能为 null：tool_audit_log.trace_id 是 NOT NULL");
        }
        this.catalog = catalog;
        this.policyEngine = policyEngine;
        this.killSwitch = killSwitch;
        this.approvalGate = approvalGate;
        this.auditLog = auditLog;
        this.idempotencyStore = idempotencyStore;
        this.ledger = ledger;
        this.argClamper = argClamper == null ? ArgClamper.NOOP : argClamper;
        this.registrationContext = registrationContext;
    }

    /** 拼出对外唯一名字。 */
    public static String namespacedName(String server, String rawToolName) {
        return ToolPolicyEngine.MCP_PREFIX + server + ":" + rawToolName;
    }

    /**
     * 纳管一个 server：只做决策，不产出可调用对象。
     *
     * <p>server 名非法时抛异常——那是配置或代码错误，
     * 与"运行期发现了一个未纳管工具"是两类问题，不该混在同一个返回值里。
     */
    public McpRegistrationResult inspect(String server) {
        requireSafe(server, "server 名");

        List<ToolCallback> raw;
        try {
            raw = catalog.fetch(server);
        } catch (RuntimeException e) {
            // 一个 MCP server 挂掉不应该让整个 Agent 起不来。
            // 但必须记下来：静默吞掉的话，运维只会看到"工具变少了"。
            auditDenied(namespacedName(server, "*"),
                    "catalog unavailable: " + e.getClass().getSimpleName());
            return McpRegistrationResult.unavailable(server,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        List<McpRegistrationResult.ManagedMcpTool> accepted = new ArrayList<>();
        List<McpRegistrationResult.Rejected> rejected = new ArrayList<>();
        Set<String> seenRaw = new HashSet<>();

        for (ToolCallback tool : raw) {
            String rawName = tool.getToolDefinition().name();

            String problem = validateRawName(rawName);
            if (problem != null) {
                reject(server, rejected, rawName, problem);
                continue;
            }
            if (!seenRaw.add(rawName)) {
                reject(server, rejected, rawName, "同一 server 内工具名重复");
                continue;
            }

            String name = namespacedName(server, rawName);
            Optional<ToolPolicy> policy = policyEngine.find(name);
            if (policy.isEmpty()) {
                reject(server, rejected, rawName, "not in allowlist");
                continue;
            }
            // 策略的来源必须匹配：一条 LOCAL 策略不该给 MCP 工具背书。
            // 否则"在本地注册一个同名策略"就成了一条绕过路径。
            if (policy.get().source() != ToolSource.MCP) {
                reject(server, rejected, rawName,
                        "policy source mismatch: expected MCP but was " + policy.get().source());
                continue;
            }
            accepted.add(new McpRegistrationResult.ManagedMcpTool(name, tool, policy.get()));
        }
        return McpRegistrationResult.of(server, accepted, rejected);
    }

    /**
     * 为某次运行产出被守卫的回调。
     *
     * <p>{@code runId} 与 {@code step} 会进幂等键，所以必须按次传入，
     * 不能在纳管时固定下来。
     *
     * @param runContext 本次运行的审计上下文。与 {@code runId} 同理必须按次传入——
     *                   审计行的 {@code trace_id} 要能指回触发它的那次排查，
     *                   而纳管时固定下来的 trace 会让所有 MCP 调用共享同一个值。
     */
    public List<ToolCallback> guardedTools(String server, String runId, int step,
                                          ToolAuditContext runContext) {
        List<ToolCallback> out = new ArrayList<>();
        for (McpRegistrationResult.ManagedMcpTool t : inspect(server).accepted()) {
            out.add(new GuardedToolCallback(t.raw(), policyEngine, killSwitch, approvalGate,
                    auditLog, runContext, idempotencyStore, ledger, argClamper, runId, step,
                    t.namespacedName()));
        }
        return out;
    }

    // ------------------------------------------------------------ 校验

    /**
     * 校验原始工具名。
     *
     * <p><b>名字是安全边界的一部分</b>，所以校验必须严格：
     * <ul>
     *   <li>含 {@code :} —— 可以伪造出 {@code mcp:other-server:tool} 这样的名字，
     *       从而冒用另一个 server 的纳管结果；</li>
     *   <li>已带 {@code mcp:} 前缀 —— 同上，且会让最终名字出现双重前缀；</li>
     *   <li>含空白 —— 在日志、提示词、命令行里都会被切开，审计对不上。</li>
     * </ul>
     */
    private static String validateRawName(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return "工具名为空";
        }
        if (rawName.contains(":")) {
            return "工具名含 ':'，可能伪造跨 server 的名字";
        }
        if (rawName.startsWith(ToolPolicyEngine.MCP_PREFIX)) {
            return "工具名不应自带 mcp: 前缀";
        }
        if (!SAFE_NAME.matcher(rawName).matches()) {
            return "工具名含非法字符（只允许字母数字与 . _ -）";
        }
        return null;
    }

    private static void requireSafe(String server, String what) {
        if (server == null || !SAFE_NAME.matcher(server).matches()) {
            throw new IllegalArgumentException(
                    what + "非法（只允许字母数字与 . _ -，不能含 ':' 或空白）：" + server);
        }
    }

    /**
     * 记一条注册期的 DENIED 事件。
     *
     * <p>风险级一律记 {@code HIGH}：走到这里的工具<b>没有策略</b>
     * （正是因为不在白名单里才被拒），而未知风险记高只会高估，
     * 记低会让「被拒的高危调用」在按风险级的统计里消失。
     * 与 {@code GuardedToolCallback} 默认拒绝路径上的取值一致。
     */
    private void auditDenied(String namespaced, String reason) {
        auditLog.record(ToolAuditEvent.denied(registrationContext, namespaced,
                ToolSource.MCP, RiskLevel.HIGH, null, reason));
    }

    private void reject(String server,
                        List<McpRegistrationResult.Rejected> rejected,
                        String rawName,
                        String reason) {
        rejected.add(new McpRegistrationResult.Rejected(rawName, reason));
        // 用带前缀的名字记审计：查审计的人需要知道是哪个 server 的哪个工具
        auditDenied(namespacedName(server, String.valueOf(rawName)), reason);
    }
}
