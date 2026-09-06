package com.oncall.app;

import com.oncall.toolgateway.ArgClamper;
import com.oncall.toolgateway.clamp.JsonScaleArgsAdapter;
import com.oncall.toolgateway.clamp.ReplicaStatePort;
import com.oncall.toolgateway.clamp.ScaleReplicasClamper;

import java.util.Objects;

/**
 * 工具网关的装配点：把「防提示注入的参数夹紧」这条链真正 new 出来。
 *
 * <p><b>这个类存在的理由是一次测量。</b>轨道 C5 建好了 {@link JsonScaleArgsAdapter}，
 * 轨道 D1 量出它的<b>生产引用数是 0</b>——
 * {@code GuardedToolCallback} 与 {@code McpToolRegistrar} 拿到的全是
 * {@code ArgClamper.NOOP}。缺的从来不是实现，是<b>有一个地方负责 new 它</b>。
 *
 * <p><b>它挡掉的具体陷阱：</b>
 * {@link ScaleReplicasClamper} 的 javadoc 自称「防提示注入后果的确定性防线」，
 * 但它接受的是 {@code ScaleRequest}，<b>不是</b>模型生成的 JSON 字符串；
 * 而网关传给 {@link ArgClamper} 的正是 JSON 字符串。
 * 于是装配的人很容易以为「把 {@code ScaleReplicasClamper} 传进去就行」——
 * 好在 {@code ScaleReplicasClamper} <b>没有</b>实现 {@code ArgClamper}，
 * 那样写编译不过。真正的形状是<b>两层</b>：
 *
 * <pre>{@code
 * 模型生成的 JSON ──> JsonScaleArgsAdapter ──> ScaleReplicasClamper ──> 夹紧后的 JSON
 *                     （解析 + 改树）          （纯算术 + 拒绝）
 * }</pre>
 *
 * <p>本类把这个两层结构写死在一个方法里，装配的人不需要自己记住它。
 *
 * <p><b>刻意不做的事：</b>不构造 {@code GuardedToolCallback}。
 * 那需要一个 {@code ApprovalGate}，而它此刻<b>生产实现数为 0</b>
 * （M1 剩余项 {@code WecomApprovalGate}）。
 * 现在硬造一个假实现来「把装配做完」，只会得到一个只有测试用的接缝——
 * 那正是轨道 C3 批评过的东西。宁可这一步只做能做完的部分。
 *
 * @see JsonScaleArgsAdapter
 * @see ScaleReplicasClamper
 */
public final class ToolGatewayAssembly {

    private ToolGatewayAssembly() {
        // 工具类：不给实例。装配是函数式的——同样的输入必须给出同样的链。
    }

    /**
     * 装配 {@code scale_replicas} 工具的参数夹紧链。
     *
     * @param replicaState 副本状态查询端口。查询失败时它必须抛异常——
     *                     {@link ScaleReplicasClamper} 的契约是「查不到就拒绝」，
     *                     <b>绝不允许「查不到就放行」</b>，
     *                     因为放行等于给提示注入留了一个绕过整道防线的入口。
     * @param limits       按服务给出的夹紧上限。不同服务的重要性不同，
     *                     {@code maxDelta} / {@code minReplicas} 必须可分别配置，
     *                     所以这里是端口而不是常量。
     * @return 可以直接交给 {@code GuardedToolCallback} / {@code McpToolRegistrar} 的夹紧器
     * @throws NullPointerException 任一参数为 null
     */
    public static ArgClamper scaleReplicasClamper(
            ReplicaStatePort replicaState,
            ScaleReplicasClamper.PolicyProvider limits) {
        Objects.requireNonNull(replicaState, "replicaState");
        Objects.requireNonNull(limits, "limits");
        return new JsonScaleArgsAdapter(replicaState, limits);
    }
}
