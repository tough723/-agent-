package com.oncall.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 进程入口。
 *
 * <p><b>这个类存在的意义是消除一个测量结果。</b>在它之前：
 *
 * <pre>{@code
 * @SpringBootApplication   = 0
 * public static void main  = 0
 * @Configuration / @Bean   = 0
 * }</pre>
 *
 * 也就是说，此前交付的 137 个生产类<b>没有任何一个能被启动</b>——
 * 全部是库与测试。这个类让「项目能不能跑起来」第一次成为一个可回答的问题。
 *
 * <p><b>它刻意不做的事：</b>不开 HTTP 端口、不自动装配数据源。
 * 本模块只提供 {@code spring-boot-starter}，所以容器起来就是一个
 * 非 Web 应用。REST 绑定在 {@code oncall-config-admin} 与 {@code oncall-tool-admin}，
 * 由它们各自的装配决定何时挂载——把端点塞进装配层会让
 * 「装配」与「对外协议」混成一件事。
 *
 * @see ToolGatewayConfiguration
 */
@SpringBootApplication
public final class OnCallApplication {

    private OnCallApplication() {
        // Spring 通过反射构造，不需要公开构造器。
    }

    public static void main(String[] args) {
        SpringApplication.run(OnCallApplication.class, args);
    }
}
