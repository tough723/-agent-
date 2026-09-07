package com.oncall.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.agent.execute.ExecutionResult;
import com.oncall.agent.llm.ModelOutputJson;
import com.oncall.agent.prompt.PromptRegistry;
import com.oncall.domain.plan.Plan;
import com.oncall.domain.run.AgentRun;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 重规划器 —— 一次执行没跑完时，决定「换个计划再试」还是「交回人工」。
 *
 * <h2>★ 为什么它必须自己扣预算，而不是只返回一个新计划</h2>
 * <p>{@link AgentRun#consumeReplan()} 返回的是一个<b>新的</b> {@code AgentRun}
 * （record 不可变）。如果本类只返回 {@link Plan}，调用方就必须记得
 * 自己去 {@code run.consumeReplan()} —— 而一旦忘了，
 * <b>重规划预算永远不会减少</b>，那个预算就等于不存在，
 * 循环可以无限改主意而每一步看起来都合法。
 *
 * <p>所以返回类型是 {@link ReplanOutcome}，<b>把「新计划」和「扣过预算的 run」
 * 绑在一起返回</b>。调用方拿到的一定是配套的两者，
 * 不可能只拿计划不扣预算。这是「靠构造保证一致」，不是「靠调用方记得」。
 *
 * <h2>★ 为什么预算检查在花钱之前</h2>
 * <p>与 {@code Executor} 的预算检查同理：先查再花。
 * 反过来（先扣再查）会在预算刚好为 0 时白白扣掉一次，
 * 让 {@code used_replans} 出现一个从未真正发生过的重规划。
 *
 * <h2>★ 为什么刻意不做兜底</h2>
 * <p>与 {@link Planner} 同一条理由，甚至更强：重规划产出的计划同样会被执行。
 * 「模型这次没给出可用计划，那就沿用上一个计划再跑一遍」<b>不是兜底，是死循环</b> ——
 * 上一个计划刚刚失败过。所以这里每一种失败都抛出，
 * 由调用方决定交回人工。
 *
 * <h2>★ 什么情况下根本不该重规划</h2>
 * <p>三种，全部在构造 {@link ReplanOutcome} 之前就拒掉：
 * <ul>
 *   <li><b>上一次已经成功</b>：没有可重规划的东西，重规划是浪费预算；</li>
 *   <li><b>上一次已交回人工</b>：人已经接手了，机器再改主意是越权；</li>
 *   <li><b>重规划预算耗尽</b>：这正是 {@code budget_replans} 存在的意义 ——
 *       没有它，循环可以无限改主意。</li>
 * </ul>
 */
public final class Replanner {

    public static final String PROMPT_NAME = "replan-generate";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel model;
    private final PromptRegistry prompts;
    private final PlanValidator validator;
    private final PlanParser parser;

    /**
     * @param model     重规划用的模型。可以与 {@link Planner} 用同一个，
     *                  但<b>不必</b>：重规划的输入更结构化（已知失败原因），
     *                  换成更便宜的小模型是合理的。
     * @param prompts   prompt 注册表
     * @param validator 与 {@link Planner} <b>必须共用同一个</b>校验器 ——
     *                  重规划产出的计划要被同一套标准校验，
     *                  否则「原计划不许做的事，重规划就能做」，闸门形同虚设。
     */
    public Replanner(ChatModel model, PromptRegistry prompts, PlanValidator validator) {
        this.model = Objects.requireNonNull(model, "model");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.parser = new PlanParser();
    }

    /**
     * 产出新计划，<b>并返回扣过重规划预算的 run</b>。
     *
     * @param run            当前 run。必须还活着（{@code RUNNING}）且预算未耗尽
     * @param alert          原始告警文本。重规划看的是原始事实，
     *                       <b>不是上一轮的输出</b> —— 上一轮输出可能已被污染。
     * @param last           上一次执行的结果。它的 {@code stoppedReason} 是重规划的核心输入：
     *                       不知道为什么失败的重规划只是在瞎猜。
     * @param availableTools 仍然可用的工具
     * @return 新计划 + 扣过预算的 run，两者配套
     * @throws ReplanNotApplicableException 上一次已成功/已交回人工/预算耗尽
     * @throws PlanProductionException      模型或输出不可用
     * @throws PlanRejectedException        新计划未通过静态校验
     */
    public ReplanOutcome replan(AgentRun run, String alert, ExecutionResult last,
                                List<String> availableTools) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(last, "last");
        Objects.requireNonNull(availableTools, "availableTools");
        if (alert == null || alert.isBlank()) {
            throw new IllegalArgumentException("alert 不能为空");
        }
        if (availableTools.isEmpty()) {
            throw new IllegalArgumentException(
                    "availableTools 为空——没有可用工具时不可能产出合法计划");
        }

        // ★ 三种「根本不该重规划」的情形，全部在扣预算之前拒掉。
        if (last.completed()) {
            throw new ReplanNotApplicableException(
                    "上一次执行已成功（SUCCEEDED），没有可重规划的东西——重规划只会白扣一次预算");
        }
        if (last.handedOver()) {
            throw new ReplanNotApplicableException(
                    "上一次已交回人工（HANDED_OVER），人已经接手了，机器再改主意是越权");
        }
        if (run.replanBudgetExhausted()) {
            throw new ReplanNotApplicableException(
                    "重规划预算已耗尽（used=" + run.usedReplans()
                            + "/budget=" + run.budgetReplans()
                            + "）——这正是 budget_replans 存在的意义：没有它，"
                            + "循环可以无限改主意而每一步看起来都合法");
        }

        // 生效版本与渲染一次拿到，理由同 Planner：分开读会撞上配置热切换。
        PromptRegistry.Rendered rendered = prompts.renderActiveWithVersion(PROMPT_NAME, Map.of(
                "alert", alert,
                "available_tools", String.join("\n", availableTools),
                "failed_reason", String.valueOf(last.stoppedReason()),
                "steps_executed", last.stepsExecuted()));

        String raw;
        try {
            ChatResponse response = model.call(new Prompt(rendered.text()));
            Generation generation = response == null ? null : response.getResult();
            raw = generation == null ? null : generation.getOutput().getText();
        } catch (RuntimeException e) {
            throw new PlanProductionException("重规划模型调用失败：" + e.getClass().getSimpleName(), e);
        }
        if (raw == null || raw.isBlank()) {
            throw new PlanProductionException("重规划模型返回了空响应", null);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(ModelOutputJson.extract(raw));
        } catch (RuntimeException | java.io.IOException e) {
            throw new PlanProductionException(
                    "重规划输出无法解析为 JSON：" + e.getClass().getSimpleName(), e);
        }
        if (root == null || !root.isObject()) {
            throw new PlanProductionException("重规划输出不是 JSON 对象", null);
        }

        Plan plan = parser.parse(root);
        // 与 Planner 用同一个校验器：重规划不该享有更宽松的标准。
        validator.validate(plan);

        // ★ 扣预算与返回新计划绑在一起。consumeReplan() 返回新的不可变 run，
        //   所以这里必须把返回值带出去，否则调用方拿到的是扣之前的旧 run。
        return new ReplanOutcome(run.consumeReplan(), plan);
    }
}
