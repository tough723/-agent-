package com.oncall.app;

import com.oncall.toolgateway.ArgClamper;
import com.oncall.toolgateway.IdempotencyStore;
import com.oncall.toolgateway.RunMode;
import com.oncall.toolgateway.KillSwitch;
import com.oncall.toolgateway.clamp.ScaleReplicasClamper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Bean 装配的测试。
 *
 * <p><b>刻意不启 Spring 容器。</b>启容器会牵出 DataSource 自动装配等
 * 一堆与「装配逻辑对不对」无关的不确定性，而且慢。
 * 这里直接实例化配置类、调它的 {@code @Bean} 方法——
 * 断言落在<b>装配产出的对象行为</b>上，而不是落在容器能不能起上。
 *
 * <p>容器能否启动是另一件事，它由 {@code OnCallApplication} 的存在
 * 与 Spring Boot 自身保证；本模块不提供数据源与 Web 端点，
 * 所以启动路径上没有本项目自己的逻辑可测。
 */
@DisplayName("ToolGatewayConfiguration：Bean 装配")
class ToolGatewayConfigurationTest {

    private final ToolGatewayConfiguration config = new ToolGatewayConfiguration();

    @Test
    @DisplayName("KillSwitch 是可直接装配的单例——每个工具各有一个实例的话，拉闸只停了一个")
    void killSwitchIsAssembled() {
        KillSwitch sw = config.killSwitch();
        // 默认必须是 FULL：一个装配出来就处于降级模式的开关，
        // 等于整个系统启动即半瘫，而没人会知道为什么。
        assertThat(sw.mode()).isEqualTo(RunMode.FULL);
    }

    @Test
    @DisplayName("幂等键计算可直接装配，且同一个调用算出同一个键")
    void idempotencyStoreIsAssembled() {
        IdempotencyStore store = config.idempotencyStore();

        String a = store.keyFor("run-1", 1, "scale_replicas", "{\"replicas\":2}");
        String b = store.keyFor("run-1", 1, "scale_replicas", "{\"replicas\":2}");
        assertThat(a).isEqualTo(b);
        assertThat(store.keyFor("run-1", 2, "scale_replicas", "{\"replicas\":2}"))
                .as("step 不同必须是不同的键")
                .isNotEqualTo(a);
    }

    @Test
    @DisplayName("★ argClamper Bean 真的会夹紧——不是「能返回对象」")
    void argClamperBeanActuallyClamps() {
        ArgClamper clamper = config.argClamper(
                service -> 4,
                service -> new ScaleReplicasClamper.Limits(3, 2));

        assertThat(clamper.clamp("scale_replicas",
                "{\"service\":\"payment\",\"replicas\":999}"))
                .contains("\"replicas\":7");
    }

    @Test
    @DisplayName("端口为 null 时装配失败，而不是造一个假的让上下文起得来")
    void argClamperRejectsNullPorts() {
        assertThatThrownBy(() -> config.argClamper(
                null, service -> new ScaleReplicasClamper.Limits(3, 2)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("replicaState");
    }
}
