package com.oncall.agent.plan;

import com.fasterxml.jackson.databind.JsonNode;
import com.oncall.domain.plan.BasisRef;
import com.oncall.domain.plan.BasisType;
import com.oncall.domain.plan.Plan;
import com.oncall.domain.plan.PlanStep;

import java.util.ArrayList;
import java.util.List;

/**
 * 把模型输出的 JSON 解析成 {@link Plan}。
 *
 * <h2>★ 为什么这是一个共享类，而不是 {@code Planner} 里的私有方法</h2>
 * <p>{@link Replanner} 也要把模型输出解析成计划。如果两边各写一份解析逻辑，
 * 那么「原计划少写一个字段会被拒」而「重规划的计划少写同一个字段会被放过」
 * 这种事就会安静地存在很久 —— <b>同一条解析规则写在两处必然分叉</b>，
 * 这是本项目已经付过多次代价的教训。
 *
 * <p>抽出来之后，{@code Planner} 与 {@code Replanner} 走的是<b>同一段代码</b>，
 * 「重规划享有更宽松的解析」在结构上就不可能。
 *
 * <h2>解析规则（与 {@link PlanValidator} 的静态校验互补）</h2>
 * <ul>
 *   <li><b>逐字段检查，不用 Jackson 直接反序列化成 record</b>：那样字段缺失会得到
 *       {@code null} 或默认值，然后一路带到执行期才炸；</li>
 *   <li><b>{@code seq} 按数组顺序重新编号</b>，不采信模型给的编号；</li>
 *   <li><b>不用 {@code asText()}</b>：{@code NullNode.asText()} 返回字符串
 *       {@code "null"}，会把一个空字段变成一个名叫 "null" 的工具；</li>
 *   <li><b>自创的 {@code basis.type} 一律拒绝</b>：默认成某一个值
 *       等于替模型编了一个授权来源。</li>
 * </ul>
 */
final class PlanParser {

    PlanParser() {
    }

    /** 把 JSON 根节点解析成 {@link Plan}。 */
    Plan parse(JsonNode root) {
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

    private List<BasisRef> parseBasis(JsonNode node, int index) {
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
    private String textual(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }
}
