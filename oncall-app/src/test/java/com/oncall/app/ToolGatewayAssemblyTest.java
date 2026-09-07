package com.oncall.app;

import com.oncall.domain.tool.ToolPolicy;
import com.oncall.toolgateway.ArgClamper;
import com.oncall.toolgateway.GuardedToolCallback;
import com.oncall.toolgateway.InMemoryApprovalRecordStore;
import com.oncall.toolgateway.InMemoryToolAuditLog;
import com.oncall.toolgateway.InMemoryToolExecutionLedger;
import com.oncall.toolgateway.KillSwitch;
import com.oncall.toolgateway.Sha256IdempotencyStore;
import com.oncall.toolgateway.ToolAuditContext;
import com.oncall.toolgateway.ToolPolicyEngine;
import com.oncall.toolgateway.clamp.ReplicaStatePort;
import com.oncall.toolgateway.clamp.ScaleReplicasClamper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 装配层的测试。
 *
 * <p><b>这一层的测试要证明的不是夹紧算术，而是「链被正确接上了」。</b>
 * 算术本身在 {@code ScaleReplicasClamperTest} / {@code JsonScaleArgsAdapterTest}
 * 里已经逐条钉住；这里断言的是<b>装配出来的东西确实会夹紧</b>——
 * 而这件事在轨道 D1 之前<b>没有任何测试覆盖</b>，
 * 因为 {@code JsonScaleArgsAdapter} 的生产引用数是 0。
 *
 * <p>替身写成内联 lambda 而不是复用 tool-gateway 的测试类：
 * CI 跑的是 {@code mvn test} 而不是 {@code package}，
 * 所以拿不到别的模块的 test-jar。
 */
@DisplayName("ToolGatewayAssembly：夹紧链的装配")
class ToolGatewayAssemblyTest {

    /** current = 4，maxDelta = 3，minReplicas = 2 ⇒ 本次上限 = 7。 */
    private static final int CURRENT = 4;
    private static final int MAX_DELTA = 3;
    private static final int MIN_REPLICAS = 2;

    private final ArgClamper clamper = ToolGatewayAssembly.scaleReplicasClamper(
            service -> CURRENT,
            service -> new ScaleReplicasClamper.Limits(MAX_DELTA, MIN_REPLICAS));

    // ------------------------------------------------------------------ 夹紧生效

    @Test
    @DisplayName("★ 装配出来的链确实会夹紧：999 被压到 current+maxDelta = 7")
    void assembledChainActuallyClamps() {
        String out = clamper.clamp("scale_replicas",
                "{\"service\":\"payment\",\"replicas\":999}");

        assertThat(out).contains("\"replicas\":7");
    }

    @Test
    @DisplayName("★ 下限同样生效：注入生成的 replicas:0 被抬到 minReplicas = 2")
    void assembledChainEnforcesTheFloor() {
        // 这正是轨道 C5 要挡的那个后果：缩到 0 等于下线服务。
        String out = clamper.clamp("scale_replicas",
                "{\"service\":\"payment\",\"replicas\":0}");

        assertThat(out).contains("\"replicas\":2");
    }

    @Test
    @DisplayName("合法区间内的请求返回**原字符串对象**，不是重新序列化的副本")
    void inRangeRequestReturnsTheSameStringObject() {
        String raw = "{\"service\":\"payment\",\"replicas\":5}";

        // isSameAs 而不是 isEqualTo：GuardedToolCallback 用字符串比较判定夹紧，
        // 重新序列化会改掉空白与键序，于是每次调用都留下一条假的 CLAMPED 审计。
        assertThat(clamper.clamp("scale_replicas", raw)).isSameAs(raw);
    }

    @Test
    @DisplayName("夹紧时保留模型带来的未知字段——丢掉它们等于篡改这次调用的记录")
    void unknownFieldsArePreserved() {
        String out = clamper.clamp("scale_replicas",
                "{\"service\":\"payment\",\"replicas\":999,\"reason\":\"traffic spike\"}");

        assertThat(out).contains("\"replicas\":7");
        assertThat(out).contains("traffic spike");
    }

    @Test
    @DisplayName("不是扩容工具就原样放过，且是同一个对象")
    void otherToolsPassThroughUntouched() {
        String raw = "{\"query\":\"cpu\"}";
        assertThat(clamper.clamp("query_metrics", raw)).isSameAs(raw);
    }

    @Test
    @DisplayName("畸形 JSON 拒绝执行，绝不原样放行")
    void malformedJsonIsRejectedNotPassedThrough() {
        // 「看不懂就放过」等于给提示注入留了一个绕过整道防线的入口。
        assertThatThrownBy(() -> clamper.clamp("scale_replicas", "{\"service\":\"payment\","))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ 装配自身

    @Test
    @DisplayName("端口为 null 立即失败，而不是等到第一次告警进来")
    void nullPortsRejected() {
        assertThatThrownBy(() -> ToolGatewayAssembly.scaleReplicasClamper(
                null, service -> new ScaleReplicasClamper.Limits(1, 1)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("replicaState");
        assertThatThrownBy(() -> ToolGatewayAssembly.scaleReplicasClamper(
                service -> 1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("limits");
    }

    // ------------------------------------------------- 完整回调的装配（轨道 D2-b）

    @Test
    @DisplayName("★ 装配出的完整回调真的会夹紧：999 到达 delegate 时已是 7")
    void assembledGuardedCallbackActuallyClamps() {
        RecordingTool tool = RecordingTool.ok("scale_replicas", "scaled");

        GuardedToolCallback guarded = ToolGatewayAssembly.guardedToolCallback(
                tool, policyAllowing("scale_replicas"), new KillSwitch(),
                new InMemoryToolAuditLog(), ToolAuditContext.of("oc-app-test"),
                new Sha256IdempotencyStore(), new InMemoryToolExecutionLedger(),
                new InMemoryApprovalRecordStore(),
                service -> CURRENT,
                service -> new ScaleReplicasClamper.Limits(MAX_DELTA, MIN_REPLICAS),
                "run-1", 1);

        guarded.call("{\"service\":\"payment\",\"replicas\":999}");

        // 这一条才是 D2-b 的验收点：不是「装配方法能返回对象」，
        // 而是「模型生成的越界参数在到达工具之前已经被压回上限」。
        assertThat(tool.receivedArgs).hasSize(1);
        assertThat(tool.receivedArgs.get(0)).contains("\"replicas\":7");
    }

    @Test
    @DisplayName("★ 原料为 null 立即失败——闸门与夹紧器都是在内部构造的，不该有机会漏")
    void assembledGuardedCallbackRejectsNullMaterials() {
        assertThatThrownBy(() -> ToolGatewayAssembly.guardedToolCallback(
                RecordingTool.ok("scale_replicas", "scaled"), policyAllowing("scale_replicas"),
                new KillSwitch(), new InMemoryToolAuditLog(), ToolAuditContext.of("oc-app-test"),
                new Sha256IdempotencyStore(), new InMemoryToolExecutionLedger(),
                null, service -> CURRENT,
                service -> new ScaleReplicasClamper.Limits(MAX_DELTA, MIN_REPLICAS),
                "run-1", 1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("approvalRecords");
    }

    @Test
    @DisplayName("装配出的回调用 delegate 自己报的工具名")
    void assembledGuardedCallbackUsesTheDelegateName() {
        GuardedToolCallback guarded = ToolGatewayAssembly.guardedToolCallback(
                RecordingTool.ok("scale_replicas", "scaled"), policyAllowing("scale_replicas"),
                new KillSwitch(), new InMemoryToolAuditLog(), ToolAuditContext.of("oc-app-test"),
                new Sha256IdempotencyStore(), new InMemoryToolExecutionLedger(),
                new InMemoryApprovalRecordStore(), service -> CURRENT,
                service -> new ScaleReplicasClamper.Limits(MAX_DELTA, MIN_REPLICAS),
                "run-1", 1);

        assertThat(guarded.getToolDefinition().name()).isEqualTo("scale_replicas");
    }

    /**
     * 把 {@code scale_replicas} 登记成只读策略。
     *
     * <p><b>这不是语义错误，是为了让断言落在夹紧上。</b>
     * 装配出来的闸门是真的 {@code PollingApprovalGate}，
     * 而高危策略会让它阻塞到审批超时（默认 10 分钟）——
     * 那样这条测试既慢又测不到夹紧。
     * 闸门本身的行为在 {@code PollingApprovalGateTest} 里已经逐条钉住。
     */
    private static ToolPolicyEngine policyAllowing(String toolName) {
        return new ToolPolicyEngine(List.of(ToolPolicy.readOnly(toolName)));
    }

    /** 记录收到的参数的最小 ToolCallback。形状抄自 GuardedToolCallbackTest.RecordingTool。 */
    private static final class RecordingTool implements ToolCallback {
        private final String name;
        private final String result;
        final List<String> receivedArgs = new ArrayList<>();

        private RecordingTool(String name, String result) {
            this.name = name;
            this.result = result;
        }

        static RecordingTool ok(String name, String result) {
            return new RecordingTool(name, result);
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
            receivedArgs.add(toolInput);
            return result;
        }
    }

    @Test
    @DisplayName("查不到副本数时拒绝，而不是放行")
    void unresolvableReplicaStateRejects() {
        ReplicaStatePort broken = service -> {
            throw new IllegalStateException("CMDB 查不到 " + service);
        };
        ArgClamper c = ToolGatewayAssembly.scaleReplicasClamper(
                broken, service -> new ScaleReplicasClamper.Limits(MAX_DELTA, MIN_REPLICAS));

        assertThatThrownBy(() -> c.clamp("scale_replicas",
                "{\"service\":\"ghost\",\"replicas\":5}"))
                .isInstanceOf(IllegalStateException.class);
    }
}
