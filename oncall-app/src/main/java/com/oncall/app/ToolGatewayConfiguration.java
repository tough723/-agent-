package com.oncall.app;

import com.oncall.toolgateway.ArgClamper;
import com.oncall.toolgateway.IdempotencyStore;
import com.oncall.toolgateway.KillSwitch;
import com.oncall.toolgateway.Sha256IdempotencyStore;
import com.oncall.toolgateway.clamp.ReplicaStatePort;
import com.oncall.toolgateway.clamp.ScaleReplicasClamper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 工具网关的 Bean 装配。
 *
 * <p><b>为什么 {@code @Bean} 方法要把端口当参数收进来，而不是自己 new 一个：</b>
 * 容器在缺 Bean 时会<b>启动即失败并说清缺哪个</b>。
 * 这与轨道 D1 把 {@code argClamper == null} 从「静默退化成不夹紧」
 * 改成「抛异常」是同一条原则——<b>装配缺件必须吵闹，不能安静地降级。</b>
 *
 * <p>反过来，如果这里自己 {@code new} 一个假的 {@link ReplicaStatePort}
 * 来让上下文起得来，得到的就是一个只有测试用的接缝，
 * 而生产环境会拿着一个永远返回固定副本数的端口去夹紧——
 * 那比装配失败糟得多。
 *
 * <h2>此刻还装不出来的部分</h2>
 * <ul>
 *   <li>{@link ReplicaStatePort} —— 需要 K8s 客户端，属于 infra 层，尚无生产实现；</li>
 *   <li>{@code ScaleReplicasClamper.PolicyProvider} —— 上限应来自配置中心，
 *       还没有把 {@code OnCallConfigRegistry} 接到这里的桥；</li>
 *   <li>{@code GuardedToolCallback} —— 它是<b>每个工具一个</b>的装饰器，
 *       不是单例，所以不该在这里做 Bean；由 {@code McpToolRegistrar}
 *       在纳管每个工具时构造。</li>
 * </ul>
 *
 * @see ToolGatewayAssembly
 */
@Configuration
public class ToolGatewayConfiguration {

    /**
     * 紧急停止开关。无依赖，所以可以直接装配。
     *
     * <p>单例是必须的：开关的意义在于「一次拉闸，全部工具停」，
     * 每个工具各有一个实例的话，拉闸就只停了一个。
     */
    @Bean
    public KillSwitch killSwitch() {
        return new KillSwitch();
    }

    /**
     * 幂等键计算。无依赖、无状态，所以可以直接装配。
     *
     * <p>注意这与「幂等<b>账本</b>」是两件事：账本（{@code ToolExecutionLedger}）
     * 在多实例下必须是数据库实现，内存实现会让幂等静默失效——
     * 所以账本刻意<b>不在</b>这里装配，它需要一个 DataSource。
     */
    @Bean
    public IdempotencyStore idempotencyStore() {
        return new Sha256IdempotencyStore();
    }

    /**
     * 参数夹紧链。两个端口都由容器注入，缺一个就启动失败。
     *
     * @see ToolGatewayAssembly#scaleReplicasClamper
     */
    @Bean
    public ArgClamper argClamper(ReplicaStatePort replicaState,
                                 ScaleReplicasClamper.PolicyProvider limits) {
        return ToolGatewayAssembly.scaleReplicasClamper(replicaState, limits);
    }
}
