package com.oncall.toolgateway.clamp;

import com.oncall.toolgateway.ArgClamper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link JsonScaleArgsAdapter} 的验收测试。
 *
 * <p><b>这个类守的是「防线真的接上了」</b>：
 * 在它出现之前 {@code implements ArgClamper} 在 {@code src/main} 里命中 0，
 * 网关拿到的一律是 {@code ArgClamper.NOOP}，
 * 而 {@code ScaleReplicasClamper} 自称的「防提示注入后果的确定性防线」一行都没生效。
 *
 * <p>三条最重要的断言：
 * <ol>
 *   <li>{@link #malformedJsonIsRejectedNotPassedThrough} —— 畸形 JSON 必须抛，
 *       不能原样放行。放行就等于给提示注入留了绕过整道防线的入口。</li>
 *   <li>{@link #unclampedArgsAreReturnedVerbatim} —— 没夹紧时必须返回<b>原字符串对象</b>。
 *       {@code GuardedToolCallback} 用字符串比较判定夹紧，
 *       重新序列化会让每次调用都留下一条假的 {@code CLAMPED} 审计。</li>
 *   <li>{@link #unknownFieldsSurviveClamping} —— 夹紧时不能丢掉模型带来的其它字段。</li>
 * </ol>
 */
class JsonScaleArgsAdapterTest {

    /** 当前副本数固定为 4 的假端口。 */
    private static ScaleReplicasClamper.PolicyProvider limits(int maxDelta, int minReplicas) {
        return service -> new ScaleReplicasClamper.Limits(maxDelta, minReplicas);
    }

    private static JsonScaleArgsAdapter adapter(int current, int maxDelta, int minReplicas) {
        return new JsonScaleArgsAdapter(service -> current, limits(maxDelta, minReplicas));
    }

    /** current=4, maxDelta=+3 ⇒ 上限 7；minReplicas=2。 */
    private static JsonScaleArgsAdapter standard() {
        return adapter(4, 3, 2);
    }

    // ------------------------------------------------------------ ★ 不放行

    @Test
    @DisplayName("★ 畸形 JSON → 抛，绝不原样放行")
    void malformedJsonIsRejectedNotPassedThrough() {
        // 这是本类最重要的一条：「看不懂就放过」等于给提示注入
        // 留了一个绕过整道防线的入口，而注入恰好擅长让模型生成畸形输出。
        for (String bad : new String[]{
                "{\"service\":\"order\", \"replicas\":",       // 截断
                "{service: order, replicas: 99}",              // 键没引号
                "{\"service\":\"order\" \"replicas\":99}",     // 少逗号
                "不是 JSON",
                "[1,2,3]",                                     // 是合法 JSON 但不是对象
                "{\"service\":\"order\",\"replicas\":99} 尾部垃圾"}) {
            assertThatThrownBy(() -> standard().clamp(ScaleReplicasClamper.TOOL_NAME, bad))
                    .as("%s 必须被拒", bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    @DisplayName("参数为空 → 抛：无法夹紧就不能放行")
    void blankArgsRejected() {
        assertThatThrownBy(() -> standard().clamp(ScaleReplicasClamper.TOOL_NAME, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> standard().clamp(ScaleReplicasClamper.TOOL_NAME, "   "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("缺 service / service 不是字符串 / service 空白 → 抛")
    void serviceIsRequired() {
        for (String bad : new String[]{
                "{\"replicas\":6}",
                "{\"service\":123,\"replicas\":6}",
                "{\"service\":\"  \",\"replicas\":6}",
                "{\"service\":null,\"replicas\":6}"}) {
            assertThatThrownBy(() -> standard().clamp(ScaleReplicasClamper.TOOL_NAME, bad))
                    .as("%s 必须被拒", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("service");
        }
    }

    @Test
    @DisplayName("缺 replicas / 不是数字 / 是小数 / 是字符串 → 抛")
    void replicasMustBeAnInteger() {
        for (String bad : new String[]{
                "{\"service\":\"order\"}",
                "{\"service\":\"order\",\"replicas\":\"6\"}",     // 字符串不当数字用
                "{\"service\":\"order\",\"replicas\":6.5}",       // 半个副本不存在
                "{\"service\":\"order\",\"replicas\":null}",
                "{\"service\":\"order\",\"replicas\":true}"}) {
            assertThatThrownBy(() -> standard().clamp(ScaleReplicasClamper.TOOL_NAME, bad))
                    .as("%s 必须被拒", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("replicas");
        }
    }

    @Test
    @DisplayName("replicas 写成 6.0 → 接受（它就是 6）")
    void integralDoubleIsAccepted() {
        assertThat(standard().clamp(ScaleReplicasClamper.TOOL_NAME,
                "{\"service\":\"order\",\"replicas\":6.0}"))
                .isEqualTo("{\"service\":\"order\",\"replicas\":6.0}");
    }

    @Test
    @DisplayName("replicas 超出 int 范围 → 抛")
    void replicasOutOfRangeRejected() {
        assertThatThrownBy(() -> standard().clamp(ScaleReplicasClamper.TOOL_NAME,
                "{\"service\":\"order\",\"replicas\":99999999999999}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("int");
    }

    @Test
    @DisplayName("★ 查不到当前副本数 → 抛，不允许「查不到就放行」")
    void replicaLookupFailureIsNotSwallowed() {
        JsonScaleArgsAdapter a = new JsonScaleArgsAdapter(
                service -> {
                    throw new IllegalStateException("k8s api unreachable");
                },
                limits(3, 2));
        assertThatThrownBy(() -> a.clamp(ScaleReplicasClamper.TOOL_NAME,
                "{\"service\":\"order\",\"replicas\":6}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreachable");
    }

    // ------------------------------------------------------------ ★ 原样返回

    @Test
    @DisplayName("★ 没发生夹紧 → 返回的是同一个字符串对象，不是重新序列化的副本")
    void unclampedArgsAreReturnedVerbatim() {
        // GuardedToolCallback 用 !args.equals(toolInput) 判定夹紧。
        // 解析再序列化会改掉空白与键序，于是每次调用都会被记成 CLAMPED——
        // 审计表里全是假的夹紧记录，而一条永远为真的断言等于没有断言。
        String raw = "{  \"service\" : \"order\",   \"replicas\": 6 ,\"reason\":\"流量上涨\" }";

        String out = standard().clamp(ScaleReplicasClamper.TOOL_NAME, raw);

        assertThat(out).isSameAs(raw);
    }

    @Test
    @DisplayName("键序不同但语义相同、且未被夹紧 → 同样原样返回")
    void keyOrderDoesNotTriggerReserialize() {
        String raw = "{\"replicas\":5,\"service\":\"order\"}";
        assertThat(standard().clamp(ScaleReplicasClamper.TOOL_NAME, raw)).isSameAs(raw);
    }

    @Test
    @DisplayName("不是 scale_replicas 的工具 → 原样放过，且不查副本数")
    void otherToolsPassThrough() {
        boolean[] portCalled = {false};
        JsonScaleArgsAdapter a = new JsonScaleArgsAdapter(
                service -> {
                    portCalled[0] = true;
                    return 4;
                },
                limits(3, 2));

        String raw = "{\"pod\":\"order-7d9f\"}";
        assertThat(a.clamp("restart_pod", raw)).isSameAs(raw);
        assertThat(portCalled[0]).as("不该为别的工具去查副本数").isFalse();
    }

    // ------------------------------------------------------------ 夹紧本身

    @Test
    @DisplayName("超过上限（current+maxDelta）→ 夹到上限")
    void clampsToUpperBound() {
        // current=4, maxDelta=3 ⇒ 上限 7；模型要 100
        String out = standard().clamp(ScaleReplicasClamper.TOOL_NAME,
                "{\"service\":\"order\",\"replicas\":100}");
        assertThat(out).contains("\"replicas\":7");
    }

    @Test
    @DisplayName("★ 注入让模型生成 replicas:0 → 夹到 minReplicas，不是 0")
    void clampsToLowerBoundInsteadOfZero() {
        // 这是整道防线存在的理由：缩到 0 等于下线服务。
        String out = standard().clamp(ScaleReplicasClamper.TOOL_NAME,
                "{\"service\":\"order\",\"replicas\":0}");
        assertThat(out).contains("\"replicas\":2");
    }

    @Test
    @DisplayName("负数同样夹到下限")
    void negativeReplicasClampedToLowerBound() {
        String out = standard().clamp(ScaleReplicasClamper.TOOL_NAME,
                "{\"service\":\"order\",\"replicas\":-50}");
        assertThat(out).contains("\"replicas\":2");
    }

    @Test
    @DisplayName("★ 夹紧时保留模型带来的其它字段——丢掉它们等于篡改这次调用的记录")
    void unknownFieldsSurviveClamping() {
        String out = standard().clamp(ScaleReplicasClamper.TOOL_NAME,
                "{\"service\":\"order\",\"replicas\":100,\"reason\":\"大促\",\"dryRun\":false}");

        assertThat(out).contains("\"replicas\":7");
        assertThat(out).contains("\"reason\":\"大促\"");
        assertThat(out).contains("\"dryRun\":false");
    }

    @Test
    @DisplayName("边界：正好等于上限不算夹紧，原样返回")
    void exactlyAtUpperBoundIsNotClamped() {
        String raw = "{\"service\":\"order\",\"replicas\":7}";
        assertThat(standard().clamp(ScaleReplicasClamper.TOOL_NAME, raw)).isSameAs(raw);
    }

    @Test
    @DisplayName("边界：正好等于下限不算夹紧")
    void exactlyAtLowerBoundIsNotClamped() {
        String raw = "{\"service\":\"order\",\"replicas\":2}";
        assertThat(standard().clamp(ScaleReplicasClamper.TOOL_NAME, raw)).isSameAs(raw);
    }

    @Test
    @DisplayName("策略按服务区分：不同服务可以有不同上下限")
    void policyIsPerService() {
        Map<String, ScaleReplicasClamper.Limits> byService = new HashMap<>();
        byService.put("order", new ScaleReplicasClamper.Limits(3, 2));
        byService.put("payment", new ScaleReplicasClamper.Limits(1, 6));
        JsonScaleArgsAdapter a = new JsonScaleArgsAdapter(
                service -> 4, byService::get);

        assertThat(a.clamp(ScaleReplicasClamper.TOOL_NAME,
                "{\"service\":\"order\",\"replicas\":100}")).contains("\"replicas\":7");
        // payment: current=4, maxDelta=1 ⇒ 上限 5；但 minReplicas=6 更高 ⇒ 夹到 6
        assertThat(a.clamp(ScaleReplicasClamper.TOOL_NAME,
                "{\"service\":\"payment\",\"replicas\":100}")).contains("\"replicas\":6");
    }

    // ------------------------------------------------------------ 构造

    @Test
    @DisplayName("clamper 为 null → 拒绝")
    void rejectsNullClamper() {
        assertThatThrownBy(() -> new JsonScaleArgsAdapter((ScaleReplicasClamper) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("它确实是一个 ArgClamper —— 这条断言就是「防线接上了」的证明")
    void itIsAnArgClamper() {
        assertThat(standard()).isInstanceOf(ArgClamper.class);
        assertThat(ArgClamper.NOOP).isNotInstanceOf(JsonScaleArgsAdapter.class);
    }
}
