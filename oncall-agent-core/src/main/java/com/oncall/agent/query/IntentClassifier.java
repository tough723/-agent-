package com.oncall.agent.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.agent.prompt.PromptRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 查询理解：<b>规则层定安全，LLM 只做路由</b>。
 *
 * <p>这是整条链路里最重要的一条分工（《查询理解与知识表示设计》§1.1）：
 *
 * <blockquote>
 * 把「要不要走审批」交给 LLM 分类，等于把安全边界交给概率。
 * LLM 的分类是"路由决策"，不是"安全决策"。安全在闸门里，不在分类器里。
 * </blockquote>
 *
 * <p>所以这里有两层，<b>顺序不可交换</b>：
 *
 * <p><b>① 规则层，在任何 LLM 调用之前。</b>
 * {@link #EXECUTE_INTENT} 命中即确定为 {@link Intent#EXECUTE}，
 * 且<b>模型无权下调</b>。{@code EXECUTE} 的召回率是硬门槛（= 1.0）：
 * 漏判一次就意味着一个高危操作绕过了审批。
 * 用正则而不是模型来保这个门槛，是因为正则是可枚举、可回归、可证明的，
 * 而模型的召回率只能统计——**安全门槛不能建在统计量上**。
 *
 * <p><b>② LLM 层，一次调用做三件事</b>：意图细化 + 查询改写 + 实体归一。
 * 合并成一次是因为分开做要 3 次 Flash、约 2,400ms，
 * 而查询理解的延迟预算是 800ms（《可行性优化与拓展设计》§6.1）。
 * 这不违反①：EXECUTE 已经在规则层判完了，这次调用不做安全决策。
 *
 * <p><b>规则层命中时仍然会调用模型</b>，代价是 EXECUTE 路径上多一次 Flash。
 * 这是刻意的：EXECUTE 走的是人工审批，延迟不是瓶颈；
 * 而留下模型的意图判断，才能度量"模型与规则层的分歧率"——
 * <b>那是调这个正则的唯一依据</b>。不留下来，正则就只能靠猜来维护。
 *
 * <p><b>降级策略：不重试、不猜。</b>
 * 模型不可用或输出不可解析时，意图兜底成 {@link Intent#OUT_OF_SCOPE}（拒绝），
 * 除非规则层已经判出 {@code EXECUTE}——那种情况下即使模型完全挂掉，
 * 请求也必须进审批闸门。
 */
public final class IntentClassifier {

    /**
     * 操作类意图的确定性判定。
     *
     * <p><b>这个正则宁可过度命中也不要漏</b>：过度命中的代价是用户被多要求一次审批
     * （可感知、可纠正），漏判的代价是一个高危操作绕过闸门（不可感知、不可挽回）。
     * 两者不对称，所以调参方向永远是往宽里调。
     *
     * <p><b>动词表是标注集逼出来的，不是拍出来的。</b>
     * 最初的 9 个动词（重启/扩容/缩容/回滚/下线/切流/删除/清理/执行）
     * 在 {@code oncall-eval} 的意图标注集上实测召回率只有 <b>10/25 = 0.400</b>：
     * 「把副本数调到 8」「让 order-service 重新起来」「把流量切到备机房」
     * 「关掉那个 pod」「把 v2.3.1 发上去」这类说法<b>一条都没命中</b>。
     * 也就是说，在补标注集之前，这个"确定性安全门槛"对大多数真实说法是失效的，
     * 而这件事在没有标注集的时候完全看不出来。
     *
     * <p><b>末尾几条句式（副本/节点/流量）是刻意加的</b>：
     * 「副本数调到 8」「加两个副本」「换个节点跑」「别再收流量」
     * 没有单一动词可以概括，只能按句式匹配。它们看起来不像"动词表"，
     * 但正则的优点恰恰是<b>可枚举、可回归</b>——每一条都能指着说清楚为什么在这。
     *
     * <p><b>过度命中是已知且可接受的</b>：标注集里的
     * {@code false-positive-probe} 组（"重启之后指标恢复了吗"这类）会全部命中。
     * 代价是多一次审批。其中「把 query.rewrite-enabled 关掉」会被判成 EXECUTE
     * 而不是 CONFIGURE——两条路径都要审批，安全上没有差别，
     * 但走错了通道，已记为待办。
     *
     * @see com.oncall.agent.query.IntentClassifier#EXECUTE_INTENT 的门槛由
     *      {@code oncall-eval} 的 {@code ExecuteRecallGate} 在 CI 里守着
     */
    public static final Pattern EXECUTE_INTENT = Pattern.compile(
            "(重启|重新(启动|起来|拉起)|拉起|启动"
                    + "|扩容|缩容|回滚|撤下|撤回"
                    + "|下线|摘掉|摘除|移除"
                    + "|切流|切换|切到|切走"
                    + "|删除|删掉|清理|清空|清一下"
                    + "|执行|关掉|关闭|停掉|停止|停用"
                    + "|发布|部署|发上去|踢掉|杀掉"
                    + "|副本.{0,4}(调|加|减|扩|缩)|(调|加|减|扩|缩).{0,4}副本"
                    + "|换个?节点|别?再?收流量|打开到)");

    /** 用哪一版 prompt。当前生效版本由 {@link PromptRegistry} 从配置读，可热切换与灰度。 */
    public static final String PROMPT_NAME = "intent-classify";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ChatModel model;
    private final PromptRegistry prompts;
    private final QuerySettings settings;

    /**
     * @param settings 生产传 {@link ConfigBackedQuerySettings}（每次调用都回配置读，
     *                 这两个键是 {@code RUNTIME_HOT}）；
     *                 评测跑批传 {@link QuerySettings#of}（整批冻结，
     *                 否则一次跑批跨了配置变更，录下来的输出就混了两组配置）
     */
    public IntentClassifier(ChatModel model, PromptRegistry prompts, QuerySettings settings) {
        this.model = Objects.requireNonNull(model, "model");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    /** 本次分类实际会用的设置。评测录制靠它写 provenance，理由见 {@link QuerySettings}。 */
    public QuerySettings settings() {
        return settings;
    }

    /**
     * @param question     用户当前这一轮的输入，不能为空
     * @param conversation 最近若干轮对话的文本表示，可以为 {@code null}（首轮）
     */
    public QueryUnderstanding classify(String question, String conversation) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("question 不能为空");
        }
        String history = conversation == null ? "" : conversation;

        // ① 规则层：在任何 LLM 调用之前，且结果不可被模型下调
        boolean ruleHit = EXECUTE_INTENT.matcher(question).find();

        // 生效版本与渲染必须一次拿到：分开读会撞上配置热切换，
        // 写进 llm_call_log 的版本就可能不是真正发出去的那段 prompt
        PromptRegistry.Rendered rendered = prompts.renderActiveWithVersion(PROMPT_NAME,
                Map.of("conversation", history, "question", question));

        // ② LLM 层
        String raw;
        try {
            ChatResponse response = model.call(new Prompt(rendered.text()));
            Generation generation = response == null ? null : response.getResult();
            raw = generation == null ? null : generation.getOutput().getText();
        } catch (RuntimeException e) {
            return fallback(ruleHit, question, rendered.version(),
                    "模型调用失败：" + e.getClass().getSimpleName());
        }
        if (raw == null || raw.isBlank()) {
            return fallback(ruleHit, question, rendered.version(), "模型返回了空响应");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(extractJson(raw));
        } catch (RuntimeException | java.io.IOException e) {
            return fallback(ruleHit, question, rendered.version(),
                    "模型输出无法解析为 JSON：" + e.getClass().getSimpleName());
        }
        if (root == null || !root.isObject()) {
            return fallback(ruleHit, question, rendered.version(), "模型输出不是 JSON 对象");
        }

        // 置信度越界视为契约违背，而不是"很有把握"
        JsonNode confNode = root.path("confidence");
        double confidence = confNode.isNumber() ? confNode.asDouble() : Double.NaN;
        if (Double.isNaN(confidence) || confidence < 0.0 || confidence > 1.0) {
            return fallback(ruleHit, question, rendered.version(),
                    "confidence 缺失或不在 [0,1]：" + confNode);
        }

        Intent llmIntent = Intent.parse(textual(root.path("intent"))).orElse(null);
        String standalone = java.util.Objects.requireNonNullElse(textual(root.path("standaloneQuery")), "");
        boolean modelWantsRewrite = root.path("rewritten").asBoolean(false);
        List<ResolvedEntity> entities = parseEntities(root.path("resolvedEntities"));

        // ③ 护栏
        List<String> fabricated = entities.stream()
                .filter(e -> !question.contains(e.text()))
                .map(ResolvedEntity::text)
                .toList();

        String suppressed = null;
        if (!settings.rewriteEnabled()) {
            suppressed = "query.rewrite-enabled 已关闭";
        } else if (!fabricated.isEmpty()) {
            // 护栏 3（只补全不替换）：模型声称消解的指代必须真的出现在原句里。
            // 有一条是编的，就说明这次输出整体不可信——
            // 于是丢掉**整个**改写，而不是只丢掉那一条实体。
            // 少丢一点换不回什么，改歪的代价是答非所问且用户看不出来。
            suppressed = "模型声称消解的指代不在原句里（疑似编造）：" + fabricated;
        } else if (confidence < settings.rewriteMinConfidence()) {
            // 护栏 2：宁可召回差，不要改歪
            suppressed = "置信度 " + confidence + " 低于阈值 " + settings.rewriteMinConfidence();
        } else if (!modelWantsRewrite) {
            suppressed = "模型自报未改写";
        } else if (standalone.isBlank()) {
            suppressed = "模型声称改写了但 standaloneQuery 为空";
        }

        boolean rewritten = suppressed == null;
        Intent intent = ruleHit ? Intent.EXECUTE
                : (llmIntent != null ? llmIntent : Intent.OUT_OF_SCOPE);
        // 注意：即使规则层已经定了 EXECUTE，模型给出闭集外的标签也要记下来。
        // 它没有影响这次的意图，但它是调 prompt 的输入——
        // 而"没影响结果"恰恰是这类问题最容易被忽略的原因。
        String rawIntentLabel = java.util.Objects.requireNonNullElse(textual(root.path("intent")), "");
        String degrade = llmIntent == null
                ? "模型返回了闭集之外的意图标签：" + (rawIntentLabel.isBlank() ? "(缺失)" : rawIntentLabel)
                : null;

        return new QueryUnderstanding(intent, ruleHit, llmIntent,
                rewritten ? standalone : question, rewritten,
                fabricated.isEmpty() ? entities : List.of(),
                confidence, rendered.version(), degrade, suppressed);
    }

    // ------------------------------------------------------------------ 内部

    /** 规则层没命中、而模型又不可用时的兜底：拒绝，但绝不放行 EXECUTE。 */
    private static QueryUnderstanding fallback(boolean ruleHit, String question,
                                               String promptVersion, String reason) {
        return QueryUnderstanding.degraded(
                ruleHit ? Intent.EXECUTE : Intent.OUT_OF_SCOPE,
                ruleHit, question, promptVersion, reason);
    }

    /**
     * 从模型输出里取出 JSON 对象。
     *
     * <p>模型经常无视"只输出 JSON"的指令，在外面包一层
     * <code>```json</code> 围栏，或者加一句"好的，以下是结果："。
     * 直接 {@code readTree} 会整段失败，然后走进降级分支——
     * 于是一次本来可用的回答被拒了，而日志里只写"无法解析"。
     * 所以这里先剥围栏、再取第一个 {@code &#123;} 到最后一个 {@code &#125;}。
     *
     * <p>这是个启发式，不是解析器。它会在"输出里有多段 JSON"时取错，
     * 但那种输出本身就是坏的，取哪一段都不对。
     */
    static String extractJson(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak > 0) {
                text = text.substring(firstBreak + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return text;
        }
        return text.substring(open, close + 1);
    }

    /**
     * 只把<b>真正的 JSON 字符串</b>读成 {@code String}，其它一律 {@code null}。
     *
     * <p><b>不能用 {@code asText()}</b>：Jackson 的 {@code NullNode.asText()} 返回
     * 字符串 {@code "null"}，{@code MissingNode.asText()} 返回 {@code ""}。
     * 于是 {@code "resolvedTo": null} 会被读成字面量 {@code "null"}，
     * 一路带到 UI 上回显成「我理解你问的是 null」——
     * 而这正是本项目在别处专门挡过的那类"看起来像模像样的坏输出"。
     */
    private static String textual(JsonNode node) {
        return node != null && node.isTextual() ? node.asText() : null;
    }

    private static List<ResolvedEntity> parseEntities(JsonNode node) {
        List<ResolvedEntity> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode e : node) {
            String text = textual(e.path("text"));
            String resolvedTo = textual(e.path("resolvedTo"));
            // 缺任一项的条目直接丢掉，而不是塞一个 null 进去：
            // 一条不完整的消解结果回显给用户，比没有回显更容易误导。
            if (text != null && !text.isBlank() && resolvedTo != null && !resolvedTo.isBlank()) {
                out.add(new ResolvedEntity(text, resolvedTo, textual(e.path("source"))));
            }
        }
        return out;
    }

    /** 便捷入口：首轮对话，没有历史。 */
    public QueryUnderstanding classify(String question) {
        return classify(question, null);
    }
}
