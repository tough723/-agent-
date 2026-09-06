package com.oncall.app;

import com.oncall.toolgateway.ArgClamper;
import com.oncall.toolgateway.clamp.ReplicaStatePort;
import com.oncall.toolgateway.clamp.ScaleReplicasClamper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
