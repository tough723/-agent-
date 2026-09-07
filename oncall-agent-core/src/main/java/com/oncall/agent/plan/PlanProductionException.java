package com.oncall.agent.plan;

/**
 * 无法产出一份计划：模型调用失败、输出为空、JSON 解析不了、结构不合契约。
 *
 * <h2>★ 与 {@link PlanRejectedException} 的区别，以及为什么必须是两个类</h2>
 * <ul>
 *   <li>{@code PlanProductionException} —— <b>没有计划</b>。模型没给出可用的东西。</li>
 *   <li>{@code PlanRejectedException} —— <b>有计划，但不许执行</b>。
 *       模型给出了东西，是静态校验判它不安全。</li>
 * </ul>
 *
 * <p>这两件事在可观测性上必须分开：前者是<b>模型可用性</b>问题
 * （该看 failover 链、限流、密钥），后者是<b>模型行为</b>问题
 * （该看 prompt、加评测用例）。混成一个异常，
 * 「模型最近老是不返回」和「模型老是想跳过探查直接扩容」
 * 在告警面板上就长得一模一样。
 *
 * <p><b>刻意不携带模型原始输出</b>：里面可能有来自告警日志的未净化文本，
 * 而这条异常最终会进日志。
 */
public class PlanProductionException extends RuntimeException {

    public PlanProductionException(String message, Throwable cause) {
        super(message, cause);
    }
}
