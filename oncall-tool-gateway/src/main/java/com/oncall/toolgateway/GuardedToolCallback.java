package com.oncall.toolgateway;

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
    private final ArgClamper argClamper;
    private final String runId;
    private final int step;

    public GuardedToolCallback(ToolCallback delegate,
                              ToolPolicyEngine policyEngine,
                              KillSwitch killSwitch,
                              ApprovalGate approvalGate,
                              ToolAuditLog auditLog,
                              IdempotencyStore idempotencyStore,
                              ArgClamper argClamper,
                              String runId,
                              int step) {
        this.delegate = delegate;
        this.policyEngine = policyEngine;
        this.killSwitch = killSwitch;
        this.approvalGate = approvalGate;
        this.auditLog = auditLog;
        this.idempotencyStore = idempotencyStore;
        this.argClamper = argClamper == null ? ArgClamper.NOOP : argClamper;
        this.runId = runId;
        this.step = step;
    }

    /** 透传：模型看到的工具定义与原始工具一致。 */
    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
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
        String toolName = delegate.getToolDefinition().name();

        // ① 默认拒绝
        ToolPolicy policy = policyEngine.resolve(toolName);

        // ② kill switch
        killSwitch.assertAllowed(toolName, policy.risk());

        // ③ 参数夹紧
        String args = argClamper.clamp(toolName, toolInput);
        if (!args.equals(toolInput)) {
            auditLog.recordClamped(key(toolName, args), toolName, toolInput, args);
        }

        // ④ 幂等：重放直接返回上次结果，不再真正执行
        String key = key(toolName, args);
        if (auditLog.has(key)) {
            return auditLog.resultOf(key);
        }

        // ⑤ 审批闸门（带超时；超时 = 升级，不是卡死）
        if (policy.requiresApproval()) {
            Approval approval = approvalGate.await(key, policy, args);
            auditLog.recordApproval(key, approval);
            if (!approval.approved()) {
                // 把拒绝原因返回给模型，让它改走别的路径，而不是整个 run 崩掉
                return denialPayload(toolName, approval);
            }
        }

        // ⑥⑦ 执行 + 审计
        try {
            String result = (toolContext == null)
                    ? delegate.call(args)
                    : delegate.call(args, toolContext);
            auditLog.recordSuccess(key, toolName, args, result);
            return result;
        } catch (RuntimeException e) {
            auditLog.recordFailure(key, toolName, args, e);
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

    /** 便捷构造：只读工具无需审批与夹紧。 */
    public static GuardedToolCallback readOnly(ToolCallback delegate,
                                               ToolPolicyEngine policyEngine,
                                               KillSwitch killSwitch,
                                               ToolAuditLog auditLog,
                                               IdempotencyStore idempotencyStore,
                                               String runId,
                                               int step) {
        return new GuardedToolCallback(delegate, policyEngine, killSwitch,
                (k, p, a) -> Approval.approved("auto"), auditLog, idempotencyStore,
                ArgClamper.NOOP, runId, step);
    }

}
