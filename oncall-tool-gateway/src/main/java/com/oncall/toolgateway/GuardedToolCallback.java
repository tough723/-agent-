package com.oncall.toolgateway;

import com.oncall.domain.tool.ToolDeniedException;
import com.oncall.domain.tool.ToolPolicy;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * 工具安全装饰器（Decorator 模式）——整个安全模型的骨架。
 *
 * <p><b>为什么用装饰器而不是注解：</b>
 * Spring AI 里任何工具（本地 {@code @Tool} 方法、{@code FunctionToolCallback}、MCP 远端工具）
 * 最终都是 {@link ToolCallback}。只要在注册入口统一套这一层，
 * 安全策略就与工具来源无关——这正好修复了原方案"注解式风险分级对 MCP 工具失效"的 P0 缺陷。
 *
 * <p><b>七道关卡，顺序不可调换：</b>
 * <pre>
 *   ① 默认拒绝   未注册工具直接拒（防 MCP 运行期新增工具 / 工具投毒）
 *   ② kill switch 只读模式下拒绝一切写操作
 *   ③ 参数夹紧   模型只提方向，数值由策略夹紧
 *   ④ 幂等       重试/重投不会二次执行
 *   ⑤ 审批闸门   高危操作阻塞等审批，带超时升级
 *   ⑥ 执行
 *   ⑦ 审计       成功/失败都落库，trace 可回放
 * </pre>
 *
 * <p><b>可叠加</b>：外层还能再套 {@code RateLimitedToolCallback}、{@code TracingToolCallback}，
 * 每层职责单一、可独立测试。
 */
public class GuardedToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final ToolPolicyEngine policyEngine;
    private final KillSwitch killSwitch;
    private final ApprovalGate approvalGate;
    private final ToolAuditLog auditLog;
    private final IdempotencyStore idempotencyStore;
    private final ToolExecutionLedger ledger;
    private final ArgClamper argClamper;
    private final String runId;
    private final int step;
    private final String toolNameOverride;

    public GuardedToolCallback(ToolCallback delegate,
                              ToolPolicyEngine policyEngine,
                              KillSwitch killSwitch,
                              ApprovalGate approvalGate,
                              ToolAuditLog auditLog,
                              IdempotencyStore idempotencyStore,
                              ToolExecutionLedger ledger,
                              ArgClamper argClamper,
                              String runId,
                              int step) {
        this(delegate, policyEngine, killSwitch, approvalGate, auditLog,
                idempotencyStore, ledger, argClamper, runId, step, null);
    }

    /**
     * @param toolNameOverride 对外与对策略统一使用的工具名；{@code null} 表示沿用
     *                         delegate 自己报的名字。<b>唯一用途是 MCP 工具加
     *                         {@code mcp:<server>:} 前缀</b>，见
     *                         {@code com.oncall.toolgateway.mcp.McpToolRegistrar}。
     */
    public GuardedToolCallback(ToolCallback delegate,
                              ToolPolicyEngine policyEngine,
                              KillSwitch killSwitch,
                              ApprovalGate approvalGate,
                              ToolAuditLog auditLog,
                              IdempotencyStore idempotencyStore,
                              ToolExecutionLedger ledger,
                              ArgClamper argClamper,
                              String runId,
                              int step,
                              String toolNameOverride) {
        this.delegate = delegate;
        this.policyEngine = policyEngine;
        this.killSwitch = killSwitch;
        this.approvalGate = approvalGate;
        this.auditLog = auditLog;
        this.idempotencyStore = idempotencyStore;
        if (ledger == null) {
            // 不给默认实现：默认给一个内存账本，等于让"忘了接数据库"
            // 在多实例下静默退化成"幂等失效"，而那正是会二次扩容的那种故障。
            throw new IllegalArgumentException("ToolExecutionLedger 不能为 null（多实例下内存实现的幂等会失效）");
        }
        this.ledger = ledger;
        this.argClamper = argClamper == null ? ArgClamper.NOOP : argClamper;
        this.runId = runId;
        this.step = step;
        this.toolNameOverride = toolNameOverride;
    }

    /**
     * 生效的工具名。
     *
     * <p><b>为什么改名要放在这个类里，而不是外面再套一层改名装饰器</b>：
     * 工具名是安全相关的——模型看到的名字与策略引擎用来判定的名字
     * <b>必须是同一个值</b>。放在同一个类里，两者由同一个字段、
     * 同一个方法产出，不存在被改岔的可能。
     * 若拆成两层装饰器，一旦包装顺序写错，就可能出现
     * 「模型看到 mcp:srv:tool、策略查的是 tool」这种正是工具投毒要利用的缝隙。
     *
     * <p>这也是 ArchUnit 规则 F9 只允许这一个 {@code ToolCallback} 实现的原因：
     * 每多一个装饰器，就多一处包装顺序出错的机会。
     */
    private String effectiveName() {
        return toolNameOverride != null ? toolNameOverride : delegate.getToolDefinition().name();
    }

    /** 透传：模型看到的工具定义与原始工具一致（除被显式改名的 MCP 工具）。 */
    @Override
    public ToolDefinition getToolDefinition() {
        ToolDefinition original = delegate.getToolDefinition();
        if (toolNameOverride == null) {
            return original;
        }
        // description 与 inputSchema 必须原样保留：改的只是名字，
        // 改了 schema 就等于让模型按错误的参数形状调用真实工具。
        return ToolDefinition.builder()
                .name(toolNameOverride)
                .description(original.description())
                .inputSchema(original.inputSchema())
                .build();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        // 用 effectiveName() 而不是 delegate 报的名字：MCP 工具必须按带前缀的名字查策略，
        // 否则 mcp:srv:tool 这条白名单永远匹配不上，而默认拒绝会让所有 MCP 工具不可用。
        String toolName = effectiveName();

        // ① 默认拒绝
        ToolPolicy policy = policyEngine.resolve(toolName);

        // ② kill switch
        killSwitch.assertAllowed(toolName, policy.risk());

        // ③ 参数夹紧
        String args = argClamper.clamp(toolName, toolInput);
        if (!args.equals(toolInput)) {
            auditLog.recordClamped(key(toolName, args), toolName, toolInput, args);
        }

        // ④ 幂等：抢占执行权。
        //
        // 【为什么是"抢占"而不是"先查后写"】
        // 原先的写法是 if (has(key)) return resultOf(key); ... recordSuccess(...)。
        // 这在并发下有窗口：两个线程都通过了 has() 检查，然后都真的执行。
        // 唯一成立的写法是让抢占本身是一次原子插入（内存版用 putIfAbsent，
        // JDBC 版靠主键冲突），这是数据库能给的保证，应用层给不了。
        String key = key(toolName, args);
        if (!ledger.claim(key, toolName)) {
            String prior = ledger.resultOf(key);
            if (prior != null) {
                return prior;   // 重放：直接返回上次结果，不再真正执行
            }
            // 已被抢占但还没有结果 = 另一次执行正在进行中。
            // 绝不并发执行同一个写操作——宁可让本次失败。
            // 二次扩容、二次重启是会出真事故的，而"这次调用失败了"只是重试一次。
            throw new ToolDeniedException(toolName, "duplicate in-flight call");
        }

        // 抢到执行权之后，任何出口都必须释放或完成，否则这个幂等键会永久停在
        // CLAIMED，之后所有重试都被判为"重复调用"——表现为"这个操作再也做不了了"。
        try {
            // ⑤ 审批闸门（带超时；超时 = 升级，不是卡死）
            if (policy.requiresApproval()) {
                Approval approval = approvalGate.await(key, policy, args);
                auditLog.recordApproval(key, approval);
                if (!approval.approved()) {
                    // 把拒绝原因返回给模型，让它改走别的路径，而不是整个 run 崩掉。
                    // 释放执行权：审批被拒不是"执行过了"，同一个请求换了审批人应该能再来。
                    ledger.release(key);
                    return denialPayload(toolName, approval);
                }
            }

            // ⑥⑦ 执行 + 审计
            try {
                String result = (toolContext == null)
                        ? delegate.call(args)
                        : delegate.call(args, toolContext);
                // 先记账本，再写审计。
                // 反过来的话，一旦审计存储写入失败，这次已经成功的执行就没有
                // 可重放的记录，下一次重试会真的再执行一遍 —— 而扩容不是幂等操作。
                // 顺序反过来之后即使 recordSuccess 抛异常，release 也只删 CLAIMED 的行，
                // 已完成的记录保得住。
                ledger.complete(key, result);
                auditLog.recordSuccess(key, toolName, args, result);
                return result;
            } catch (RuntimeException e) {
                auditLog.recordFailure(key, toolName, args, e);
                // 失败必须可重试：删掉抢占行，否则这个幂等键就废了
                ledger.release(key);
                throw e;
            }
        } catch (RuntimeException e) {
            // 兜底释放。走到这里有三种情况：
            //   a) 审批闸门自己抛异常（例如企微不可达）—— 必须释放，否则永久卡死；
            //   b) 审批被拒 —— 上面已经 release 过，这里 release 是幂等的空操作；
            //   c) complete() 已经成功、随后 recordSuccess 抛异常 ——
            //      这种情况**绝对不能**释放，否则一次已经成功的扩容会失去可重放记录。
            // 用 isCompleted 把 c 与 a 区分开，而不是靠"谁先谁后"的心算。
            if (!ledger.isCompleted(key)) {
                ledger.release(key);
            }
            throw e;
        }
    }

    private String key(String toolName, String args) {
        return idempotencyStore.keyFor(runId, step, toolName, canonical(args));
    }

    /**
     * 参数规范化：JSON key 排序 + 去空白。
     * 不做规范化会导致同语义不同字面量产生不同幂等键，幂等失效。
     * 这里给出最小实现；生产建议用 Jackson 读成 TreeMap 再序列化。
     */
    static String canonical(String json) {
        if (json == null) {
            return "";
        }
        return json.replaceAll("\\s+", "");
    }

    private String denialPayload(String toolName, Approval approval) {
        return "{\"denied\":true,\"tool\":\"" + toolName
                + "\",\"expired\":" + approval.expired()
                + ",\"reason\":\"" + String.valueOf(approval.reason()).replace("\"", "'")
                + "\",\"hint\":\"该操作未获批准，请改用只读手段继续排查，或转人工处理。\"}";
    }

    /** 便于测试与调试：暴露被包装的原始工具。 */
    public ToolCallback delegate() {
        return delegate;
    }

    /** 生效的工具名（MCP 工具带 {@code mcp:<server>:} 前缀）。 */
    public String toolName() {
        return effectiveName();
    }

    /** 便捷构造：只读工具无需审批与夹紧。 */
    public static GuardedToolCallback readOnly(ToolCallback delegate,
                                               ToolPolicyEngine policyEngine,
                                               KillSwitch killSwitch,
                                               ToolAuditLog auditLog,
                                               IdempotencyStore idempotencyStore,
                                               ToolExecutionLedger ledger,
                                               String runId,
                                               int step) {
        return new GuardedToolCallback(delegate, policyEngine, killSwitch,
                (k, p, a) -> Approval.granted("auto"), auditLog, idempotencyStore,
                ledger, ArgClamper.NOOP, runId, step);
    }

}
