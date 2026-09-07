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

    public Planner(ChatModel model, PromptRegistry prompts, PlanValidator validator) {
        this.model = Objects.requireNonNull(model, "model");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.validator = Objects.requireNonNull(validator, "validator");
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
     * 把 JSON 转成 {@link Plan}。
     *
     * <p><b>刻意不用 Jackson 直接反序列化成 record</b>：那样字段缺失会得到
     * {@code null} 或默认值，然后一路带到执行期才炸。这里逐字段检查，
     * 让「模型少写了一个字段」在解析期就变成一个说得清楚的错误。
     */
    private static Plan parsePlan(JsonNode root) {
        JsonNode stepsNode = root.path("steps");
        if (!stepsNode.isArray()) {
            throw new PlanProductionException(
                    "模型输出缺少 steps 数组（实际是 " + stepsNode.getNodeType() + "）", null);
        }
        List<PlanStep> steps = new ArrayList<>();
        int index = 0;
        for (JsonNode s : stepsNode) {
            index++;
            if (!s.isObject()) {
                throw new PlanProductionException("steps[" + index + "] 不是 JSON 对象", null);
            }
            String action = textual(s.path("action"));
            if (action == null) {
                throw new PlanProductionException(
                        "steps[" + index + "] 缺少 action 或 action 不是字符串", null);
            }
            // seq 由本类按数组顺序重新编号，不采信模型给的 seq：
            // 模型经常给出 0 起、跳号或重复的编号，而 Plan 的构造期不变量
            // 要求 1..n 连续——采信它只会把一个「模型输出不规范」的问题
            // 变成一个看起来像领域错误的异常。
            JsonNode argsNode = s.path("args");
            String argsJson = argsNode.isObject() ? argsNode.toString() : "{}";
            steps.add(new PlanStep(index, action, argsJson, parseBasis(s.path("basis"), index)));
        }
        try {
            return new Plan(steps);
        } catch (IllegalArgumentException e) {
            // Plan 的构造期不变量（非空、seq 连续）在这里转成本层的语言。
            throw new PlanProductionException("模型输出的计划结构不合法：" + e.getMessage(), e);
        }
    }

    private static List<BasisRef> parseBasis(JsonNode node, int index) {
        if (!node.isArray()) {
            throw new PlanProductionException("steps[" + index + "] 缺少 basis 数组——"
                    + "每一步都必须声明依据来源，否则无法判断高危动作是否可信", null);
        }
        List<BasisRef> refs = new ArrayList<>();
        for (JsonNode b : node) {
            String type = textual(b.path("type"));
            String ref = textual(b.path("ref"));
            if (type == null) {
                throw new PlanProductionException(
                        "steps[" + index + "] 的 basis 项缺少 type", null);
            }
            BasisType parsed;
            try {
                parsed = BasisType.valueOf(type);
            } catch (IllegalArgumentException e) {
                // 自创的依据类型必须拒绝：默认成某一个值等于替模型编了一个授权来源。
                throw new PlanProductionException("steps[" + index + "] 的 basis.type=\""
                        + type + "\" 不是合法取值（只允许 ALERT_RULE / RUNBOOK / LOG_TEXT / TOOL_OUTPUT）",
                        null);
            }
            if (ref == null) {
                throw new PlanProductionException("steps[" + index + "] 的 basis 项缺少 ref——"
                        + "依据必须可追溯到具体出处", null);
            }
            refs.add(new BasisRef(parsed, ref));
        }
        return refs;
    }

    /**
     * 只把<b>真正的 JSON 字符串</b>读成 {@code String}，其它一律 {@code null}。
     *
     * <p><b>不能用 {@code asText()}</b>：Jackson 的 {@code NullNode.asText()} 返回
     * 字符串 {@code "null"}，{@code MissingNode.asText()} 返回 {@code ""}。
     * 于是 {@code "action": null} 会被读成字面量 {@code "null"}，
     * 然后被当成一个名叫 "null" 的工具一路带到白名单校验。
     */
    private static String textual(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
