package com.oncall.agent.execute;

import org.springframework.ai.tool.ToolCallback;

import java.util.Optional;

/**
 * 按工具名找到可执行的回调。
 *
 * <p>为什么是个端口而不是直接注入 {@code Map<String, ToolCallback>}：
 * 生产装配时这些回调是 {@code GuardedToolCallback}（外面裹了审批、审计、
 * 幂等、夹紧），而测试里只需要一个返回固定字符串的替身。
 * 用 Map 会把「装配时怎么裹」这件事泄进 Executor 的构造签名。
 *
 * <p><b>返回 {@code Optional} 而不是抛异常</b>：工具不存在对 Executor 来说
 * 是「这一步做不了、该交回人工」，不是程序错误。
 * 与 {@code ToolPolicyEngine.resolve()} 的语义刻意不同——
 * 那个是白名单校验（不在名单里就该拒），这个是装配查找（没装配就是没装配）。
 */
@FunctionalInterface
public interface ToolResolver {

    Optional<ToolCallback> resolve(String toolName);
}
