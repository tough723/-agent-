package com.oncall.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.agent.llm.ModelOutputJson;
import com.oncall.agent.prompt.PromptRegistry;
import com.oncall.domain.plan.BasisRef;
import com.oncall.domain.plan.BasisType;
import com.oncall.domain.plan.Plan;
import com.oncall.domain.plan.PlanStep;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把一次告警变成一份可执行的排查计划。
 *
 * <h2>★ 为什么本类<b>没有</b>降级分支</h2>
 * <p>{@code IntentClassifier} 有 {@code fallback(...)}：模型不可用时退回规则层，
 * 因为意图分类错了最多是答非所问，而且它下面确实有一个规则层可以兜。
 *
 * <p><b>Planner 不能这样。</b>计划会被逐步执行，而「一份兜底计划」这种东西不存在——
 * 任何固定的默认计划都等于「不管什么告警都执行同一套动作」，
 * 那比不产出计划危险得多。所以本类的每一种失败都<b>抛出</b>：
 * 模型调用失败、输出为空、JSON 解析不了、结构不合契约、静态校验不过。
 *
 * <p>调用方拿到异常后应当做的是<b>把这次排查交回给人</b>，
 * 而不是换一份计划继续跑。
 *
 * <h2>产出即校验</h2>
 * <p>{@code validate} 在本类内部调用，不交给调用方。
 * 理由是「Planner 产出的计划一定是过校验的」应当是<b>类型级别的保证</b>，
 * 而不是「调用方记得调一下」的约定——本项目已多次因为把安全边界
 * 交给调用方记得而出问题。
 */
public final class Planner {

    public static final String PROMPT_NAME = "plan-generate";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel model;
    private final PromptRegistry prompts;
    private final PlanValidator validator;
    private final PlanParser parser;

    public Planner(ChatModel model, PromptRegistry prompts, PlanValidator validator) {
        this.model = Objects.requireNonNull(model, "model");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.validator = Objects.requireNonNull(validator, "validator");
        this.parser = new PlanParser();
    }

    /**
     * 生成并校验一份计划。
     *
     * @param alert          本次告警的文本描述
     * @param availableTools 本次允许使用的工具名，会原样渲染进 prompt
     * @throws PlanProductionException 模型或输出不可用
     * @throws PlanRejectedException   计划未通过静态校验
     */
    public Plan plan(String alert, List<String> availableTools) {
        if (alert == null || alert.isBlank()) {
            throw new IllegalArgumentException("alert 不能为空");
        }
        Objects.requireNonNull(availableTools, "availableTools");
        if (availableTools.isEmpty()) {
            // 没有可用工具时任何计划都过不了白名单校验，
            // 与其让模型白跑一次，不如在这里就把话说清楚。
            throw new IllegalArgumentException(
                    "availableTools 为空——没有可用工具时不可能产出合法计划");
        }

        // 生效版本与渲染必须一次拿到：分开读会撞上配置热切换，
        // 记录下来的版本就可能不是真正发出去的那段 prompt。
        PromptRegistry.Rendered rendered = prompts.renderActiveWithVersion(PROMPT_NAME,
                Map.of("alert", alert, "available_tools", String.join("\n", availableTools)));

        String raw;
        try {
            ChatResponse response = model.call(new Prompt(rendered.text()));
            Generation generation = response == null ? null : response.getResult();
            raw = generation == null ? null : generation.getOutput().getText();
        } catch (RuntimeException e) {
            throw new PlanProductionException("模型调用失败：" + e.getClass().getSimpleName(), e);
        }
        if (raw == null || raw.isBlank()) {
            throw new PlanProductionException("模型返回了空响应", null);
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(ModelOutputJson.extract(raw));
        } catch (RuntimeException | java.io.IOException e) {
            throw new PlanProductionException(
                    "模型输出无法解析为 JSON：" + e.getClass().getSimpleName(), e);
        }
        if (root == null || !root.isObject()) {
            throw new PlanProductionException("模型输出不是 JSON 对象", null);
        }

        Plan plan = parsePlan(root);
        // ★ 产出即校验：不交给调用方记得。
        validator.validate(plan);
        return plan;
    }

    /**
     * 解析委托给 {@link PlanParser} —— 与 {@link Replanner} <b>共用同一段解析代码</b>，
     * 这样「重规划享有更宽松的解析」在结构上就不可能。
     */
    private Plan parsePlan(JsonNode root) {
        return parser.parse(root);
    }
}