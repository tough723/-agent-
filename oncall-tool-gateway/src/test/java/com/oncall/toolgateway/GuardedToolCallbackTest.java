package com.oncall.toolgateway;

import com.oncall.domain.tool.ToolDeniedException;
import com.oncall.domain.tool.ToolPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具安全装饰器的验收测试——对应修复方案 F1.7 的验收标准。
 *
 * <p>这个类是整个安全模型的骨架，七道关卡**顺序不可调换**，
 * 所以这里不只测「每道关卡单独生效」，还要测**关卡之间的先后关系**：
 * 例如未注册工具必须在 kill switch 之前被拒（否则未注册工具的存在性会被泄露），
 * kill switch 必须在参数夹紧之前（否则被禁的工具还会留下夹紧审计，制造噪音与误导）。
 *
 * <p>全部用 fake 依赖，不启 Spring 上下文——装饰器逻辑本身不该依赖容器。
 */
class GuardedToolCallbackTest {

    private static final String TOOL = "scale_replicas";
    private static final String READ_TOOL = "query_metrics";

    private ToolPolicyEngine policyEngine;
    private KillSwitch killSwitch;
    private RecordingAudit audit;
    private IdempotencyStore idempotency;

    @BeforeEach
    void setUp() {
        policyEngine = new ToolPolicyEngine(List.of(
                ToolPolicy.highRisk(TOOL, Duration.ofMinutes(15)),
                ToolPolicy.readOnly(READ_TOOL)
        ));
        killSwitch = new KillSwitch();
        audit = new RecordingAudit();
        idempotency = new Sha256IdempotencyStore();
    }

    // ---------------------------------------------------------- ① 默认拒绝

    @Test
    @DisplayName("① 未注册工具被拒，且原始工具根本不会被调用")
    void unregisteredToolIsDeniedAndNeverExecuted() {
        RecordingTool tool = RecordingTool.ok("rogue_tool", "pwned");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        assertThatThrownBy(() -> guarded.call("{}"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("rogue_tool")
                .hasMessageContaining("not in allowlist");

        assertThat(tool.calls).isZero();
        assertThat(audit.results).isEmpty();
    }

    @Test
    @DisplayName("① 策略引擎的拒绝回调被触发——MCP 运行期偷偷新增工具要能告警")
    void denialListenerIsNotified() {
        List<String> denied = new ArrayList<>();
        ToolPolicyEngine engine = new ToolPolicyEngine(
                List.of(ToolPolicy.readOnly(READ_TOOL)),
                (tool, reason) -> denied.add(tool + ":" + reason));

        GuardedToolCallback guarded = new GuardedToolCallback(
                RecordingTool.ok("sneaky_mcp_tool", "x"), engine, killSwitch,
                autoApprove(), audit, idempotency, ArgClamper.NOOP, "run-1", 1);

        assertThatThrownBy(() -> guarded.call("{}")).isInstanceOf(ToolDeniedException.class);
        assertThat(denied).containsExactly("sneaky_mcp_tool:not in allowlist");
    }

    @Test
    @DisplayName("① 撤销注册后立即失效，不需要重启")
    void revokedToolIsDeniedImmediately() {
        RecordingTool tool = RecordingTool.ok(TOOL, "ok");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        policyEngine.revoke(TOOL);

        assertThatThrownBy(() -> guarded.call("{}")).isInstanceOf(ToolDeniedException.class);
        assertThat(tool.calls).isZero();
    }

    // ---------------------------------------------------------- ② kill switch

    @Test
    @DisplayName("② OFF 模式下连只读工具也拒绝")
    void offModeBlocksEverything() {
        killSwitch.set(RunMode.OFF);
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "metrics");

        assertThatThrownBy(() -> guard(tool, ArgClamper.NOOP, autoApprove()).call("{}"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("mode=OFF");
        assertThat(tool.calls).isZero();
    }

    @Test
    @DisplayName("② READ_ONLY 模式下写工具被拒")
    void readOnlyModeBlocksWriteTools() {
        killSwitch.set(RunMode.READ_ONLY);
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");

        assertThatThrownBy(() -> guard(tool, ArgClamper.NOOP, autoApprove()).call("{}"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("write tool blocked");
        assertThat(tool.calls).isZero();
    }

    @Test
    @DisplayName("② READ_ONLY 模式下只读工具照常放行")
    void readOnlyModeAllowsReadTools() {
        killSwitch.set(RunMode.READ_ONLY);
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "metrics");

        String result = guard(tool, ArgClamper.NOOP, autoApprove()).call("{}");

        assertThat(result).isEqualTo("metrics");
        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("② kill switch 是热生效的：切档后下一次调用立即改变行为，无需重建装饰器")
    void killSwitchTakesEffectWithoutRebuild() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        assertThat(guarded.call("{\"replicas\":3}")).isEqualTo("scaled");

        killSwitch.set(RunMode.READ_ONLY);

        assertThatThrownBy(() -> guarded.call("{\"replicas\":4}"))
                .isInstanceOf(ToolDeniedException.class);
    }

    // ---------------------------------------------------------- ③ 参数夹紧

    @Test
    @DisplayName("③ 原始工具收到的是夹紧后的参数，不是模型生成的参数")
    void delegateReceivesClampedArgs() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        ArgClamper clamper = (name, raw) -> "{\"replicas\":2}";

        guard(tool, clamper, autoApprove()).call("{\"replicas\":0}");

        assertThat(tool.receivedArgs).containsExactly("{\"replicas\":2}");
    }

    @Test
    @DisplayName("③ 发生夹紧时留下审计——这通常意味着模型生成了越界参数")
    void clampingIsAudited() {
        ArgClamper clamper = (name, raw) -> "{\"replicas\":2}";

        guard(RecordingTool.ok(TOOL, "scaled"), clamper, autoApprove()).call("{\"replicas\":0}");

        assertThat(audit.events).anyMatch(e -> e.startsWith("CLAMPED:"));
        assertThat(audit.lastClampedRaw).isEqualTo("{\"replicas\":0}");
        assertThat(audit.lastClampedNew).isEqualTo("{\"replicas\":2}");
    }

    @Test
    @DisplayName("③ 未发生夹紧时不产生夹紧审计，避免噪音")
    void noClampNoAudit() {
        guard(RecordingTool.ok(TOOL, "scaled"), ArgClamper.NOOP, autoApprove())
                .call("{\"replicas\":2}");

        assertThat(audit.events).noneMatch(e -> e.startsWith("CLAMPED:"));
    }

    @Test
    @DisplayName("③ 审批人看到的必须是夹紧后的参数——否则批的不是真正会执行的东西")
    void approverSeesClampedArgs() {
        RecordingGate gate = new RecordingGate(Approval.granted("alice"));
        ArgClamper clamper = (name, raw) -> "{\"replicas\":2}";

        guard(RecordingTool.ok(TOOL, "scaled"), clamper, gate).call("{\"replicas\":999}");

        assertThat(gate.lastArgs).isEqualTo("{\"replicas\":2}");
    }

    // ---------------------------------------------------------- ④ 幂等

    @Test
    @DisplayName("④ 同一幂等键重复调用只执行一次，第二次直接返回上次结果")
    void replayDoesNotReExecute() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled-to-3");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        String first = guarded.call("{\"replicas\":3}");
        String second = guarded.call("{\"replicas\":3}");

        assertThat(first).isEqualTo("scaled-to-3");
        assertThat(second).isEqualTo("scaled-to-3");
        assertThat(tool.calls).as("重试不能导致二次扩容").isEqualTo(1);
    }

    @Test
    @DisplayName("④ 参数字面量不同但语义相同时也算同一次调用（规范化生效）")
    void whitespaceDifferenceDoesNotBreakIdempotency() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled-to-3");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        guarded.call("{\"replicas\": 3}");
        guarded.call("{\"replicas\":3}");

        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("④ 不同步序号视为不同调用，不会被幂等误吞")
    void differentStepIsNotSwallowed() {
        RecordingTool tool = RecordingTool.ok(TOOL, "ok");

        guardStep(tool, 1).call("{\"replicas\":3}");
        guardStep(tool, 2).call("{\"replicas\":3}");

        assertThat(tool.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("④ 规范化只去空白，不改变语义")
    void canonicalStripsWhitespaceOnly() {
        assertThat(GuardedToolCallback.canonical("{ \"a\" : 1 , \"b\" : 2 }"))
                .isEqualTo("{\"a\":1,\"b\":2}");
        assertThat(GuardedToolCallback.canonical(null)).isEmpty();
    }

    // ---------------------------------------------------------- ⑤ 审批闸门

    @Test
    @DisplayName("⑤ 审批被拒时不执行，并把拒绝原因返回给模型让它改走别的路径")
    void rejectedApprovalBlocksExecutionAndReturnsHint() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        RecordingGate gate = new RecordingGate(Approval.rejected("alice", "变更窗口内禁止扩容"));

        String result = guard(tool, ArgClamper.NOOP, gate).call("{\"replicas\":5}");

        assertThat(tool.calls).isZero();
        assertThat(result).contains("\"denied\":true").contains("变更窗口内禁止扩容").contains("hint");
        assertThat(audit.events).anyMatch(e -> e.startsWith("APPROVAL:"));
        assertThat(audit.lastApproval.approved()).isFalse();
    }

    @Test
    @DisplayName("⑤ 审批超时不是卡死：expired 标记回传给模型，用于触发升级")
    void expiredApprovalIsSignalledNotStuck() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        RecordingGate gate = new RecordingGate(Approval.timedOut());

        String result = guard(tool, ArgClamper.NOOP, gate).call("{\"replicas\":5}");

        assertThat(tool.calls).isZero();
        assertThat(result).contains("\"denied\":true").contains("\"expired\":true");
    }

    @Test
    @DisplayName("⑤ 审批通过后正常执行")
    void approvedExecutionProceeds() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        RecordingGate gate = new RecordingGate(Approval.granted("alice"));

        assertThat(guard(tool, ArgClamper.NOOP, gate).call("{\"replicas\":5}")).isEqualTo("scaled");
        assertThat(gate.calls).isEqualTo(1);
        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("⑤ 只读工具不走审批闸门")
    void readOnlyToolSkipsApproval() {
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "metrics");
        RecordingGate gate = new RecordingGate(Approval.granted("alice"));

        guard(tool, ArgClamper.NOOP, gate).call("{}");

        assertThat(gate.calls).isZero();
        assertThat(tool.calls).isEqualTo(1);
    }

    // ---------------------------------------------------------- ⑥⑦ 执行与审计

    @Test
    @DisplayName("⑥⑦ 成功执行后落审计，且结果可被后续重放取回")
    void successIsAudited() {
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "cpu=95%");

        guard(tool, ArgClamper.NOOP, autoApprove()).call("{\"service\":\"order\"}");

        assertThat(audit.events).anyMatch(e -> e.startsWith("SUCCESS:"));
        assertThat(audit.lastResult).isEqualTo("cpu=95%");
        assertThat(audit.lastArgs).isEqualTo("{\"service\":\"order\"}");
        assertThat(audit.results).hasSize(1);
    }

    @Test
    @DisplayName("⑥⑦ 执行失败也落审计，且异常继续抛出（不能被吞掉）")
    void failureIsAuditedAndRethrown() {
        RuntimeException boom = new IllegalStateException("下游超时");
        RecordingTool tool = RecordingTool.failing(TOOL, boom);

        assertThatThrownBy(() -> guard(tool, ArgClamper.NOOP, autoApprove()).call("{\"replicas\":3}"))
                .isSameAs(boom);

        assertThat(audit.events).anyMatch(e -> e.startsWith("FAILURE:"));
        assertThat(audit.lastError).isSameAs(boom);
        assertThat(audit.results).as("失败的执行不能被当成可重放的成功结果").isEmpty();
    }

    // ---------------------------------------------------------- 关卡顺序

    @Test
    @DisplayName("顺序：① 默认拒绝在 ② kill switch 之前——不泄露未注册工具的存在性")
    void defaultDenyRunsBeforeKillSwitch() {
        killSwitch.set(RunMode.OFF);

        assertThatThrownBy(() -> guard(RecordingTool.ok("rogue", "x"), ArgClamper.NOOP, autoApprove()).call("{}"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("not in allowlist")
                .hasMessageNotContaining("mode=OFF");
    }

    @Test
    @DisplayName("顺序：② kill switch 在 ③ 参数夹紧之前——被禁的工具不该留下夹紧审计")
    void killSwitchRunsBeforeClamping() {
        killSwitch.set(RunMode.OFF);
        RecordingClamper clamper = new RecordingClamper("{\"replicas\":2}");

        assertThatThrownBy(() -> guard(RecordingTool.ok(TOOL, "x"), clamper, autoApprove()).call("{}"))
                .isInstanceOf(ToolDeniedException.class);

        assertThat(clamper.calls).as("被禁工具不应触发夹紧").isZero();
        assertThat(audit.events).noneMatch(e -> e.startsWith("CLAMPED:"));
    }

    @Test
    @DisplayName("顺序：④ 幂等在 ⑤ 审批之前——重放不该再去打扰审批人")
    void idempotencyRunsBeforeApproval() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        RecordingGate gate = new RecordingGate(Approval.granted("alice"));
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, gate);

        guarded.call("{\"replicas\":3}");
        guarded.call("{\"replicas\":3}");

        assertThat(gate.calls).as("重放不应二次请求审批").isEqualTo(1);
    }

    // ---------------------------------------------------------- 便捷构造与透传

    @Test
    @DisplayName("readOnly 便捷构造：自动放行、不夹紧，适用于只读工具")
    void readOnlyFactoryWiresPermissiveDefaults() {
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "metrics");

        GuardedToolCallback guarded = GuardedToolCallback.readOnly(
                tool, policyEngine, killSwitch, audit, idempotency, "run-1", 1);

        assertThat(guarded.call("{}")).isEqualTo("metrics");
        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("工具定义原样透传——装饰器不能改变模型看到的工具签名")
    void toolDefinitionIsPassedThrough() {
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "metrics");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        assertThat(guarded.getToolDefinition().name()).isEqualTo(READ_TOOL);
        assertThat(guarded.getToolMetadata()).isNotNull();
        assertThat(guarded.delegate()).isSameAs(tool);
    }

    // ------------------------------------------------------------------ helpers

    private GuardedToolCallback guard(ToolCallback delegate, ArgClamper clamper, ApprovalGate gate) {
        return new GuardedToolCallback(delegate, policyEngine, killSwitch, gate,
                audit, idempotency, clamper, "run-1", 1);
    }

    private GuardedToolCallback guardStep(ToolCallback delegate, int step) {
        return new GuardedToolCallback(delegate, policyEngine, killSwitch, autoApprove(),
                audit, idempotency, ArgClamper.NOOP, "run-1", step);
    }

    private static ApprovalGate autoApprove() {
        return (key, policy, args) -> Approval.granted("auto");
    }

    /** 记录被调用情况的假工具。 */
    private static final class RecordingTool implements ToolCallback {
        private final String name;
        private final String result;
        private final RuntimeException failure;
        final List<String> receivedArgs = new ArrayList<>();
        int calls;

        private RecordingTool(String name, String result, RuntimeException failure) {
            this.name = name;
            this.result = result;
            this.failure = failure;
        }

        static RecordingTool ok(String name, String result) {
            return new RecordingTool(name, result, null);
        }

        static RecordingTool failing(String name, RuntimeException failure) {
            return new RecordingTool(name, null, failure);
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("测试工具 " + name)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            receivedArgs.add(toolInput);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    /** 记录全部审计事件的假审计日志，同时充当幂等结果存储。 */
    private static final class RecordingAudit implements ToolAuditLog {
        final Map<String, String> results = new HashMap<>();
        final List<String> events = new ArrayList<>();
        String lastClampedRaw;
        String lastClampedNew;
        Approval lastApproval;
        String lastResult;
        String lastArgs;
        Throwable lastError;

        @Override
        public boolean has(String idempotencyKey) {
            return results.containsKey(idempotencyKey);
        }

        @Override
        public String resultOf(String idempotencyKey) {
            return results.get(idempotencyKey);
        }

        @Override
        public void recordApproval(String idempotencyKey, Approval approval) {
            events.add("APPROVAL:" + idempotencyKey);
            lastApproval = approval;
        }

        @Override
        public void recordSuccess(String idempotencyKey, String toolName, String args, String result) {
            events.add("SUCCESS:" + idempotencyKey);
            results.put(idempotencyKey, result);
            lastResult = result;
            lastArgs = args;
        }

        @Override
        public void recordFailure(String idempotencyKey, String toolName, String args, Throwable error) {
            events.add("FAILURE:" + idempotencyKey);
            lastError = error;
        }

        @Override
        public void recordClamped(String idempotencyKey, String toolName, String rawArgs, String clampedArgs) {
            events.add("CLAMPED:" + idempotencyKey);
            lastClampedRaw = rawArgs;
            lastClampedNew = clampedArgs;
        }

        @Override
        public void recordDenied(String toolName, String reason) {
            events.add("DENIED:" + toolName + ":" + reason);
        }
    }

    /** 固定返回某个审批结果的假闸门。 */
    private static final class RecordingGate implements ApprovalGate {
        private final Approval result;
        int calls;
        String lastArgs;

        RecordingGate(Approval result) {
            this.result = result;
        }

        @Override
        public Approval await(String idempotencyKey, ToolPolicy policy, String args) {
            calls++;
            lastArgs = args;
            return result;
        }
    }

    /** 记录调用次数的假夹紧器。 */
    private static final class RecordingClamper implements ArgClamper {
        private final String clamped;
        int calls;

        RecordingClamper(String clamped) {
            this.clamped = clamped;
        }

        @Override
        public String clamp(String toolName, String rawArgs) {
            calls++;
            return clamped;
        }
    }
}
