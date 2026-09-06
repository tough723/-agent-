package com.oncall.toolgateway.clamp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oncall.toolgateway.ArgClamper;

import java.math.BigDecimal;

/**
 * 把 {@link ArgClamper} 的「字符串进、字符串出」适配到
 * {@link ScaleReplicasClamper} 的「{@code ScaleRequest} 进、{@code ClampResult} 出」。
 *
 * <h2>为什么这一层此前是缺的</h2>
 *
 * <p>{@code ScaleReplicasClamper} 的 javadoc 自称「防提示注入后果的确定性防线」，
 * 而它<b>刻意不解析 JSON</b>——它只接受一个 {@code ScaleRequest}。
 * 但网关传给 {@link ArgClamper} 的是模型生成的原始 JSON 字符串。
 * 两侧对不上，于是 {@code implements ArgClamper} 在 {@code src/main} 里
 * <b>命中 0</b>，{@code GuardedToolCallback} 与 {@code McpToolRegistrar}
 * 拿到的都是 {@code ArgClamper.NOOP}——**那道防线一行都没接上**。
 *
 * <p>这与轨道 C3 的 {@code ApprovalGate}（0 实现）、C4 的 {@code traceId}（0 产出方）
 * 是同一个缺陷类：<b>声明存在，实现缺席</b>。
 *
 * <h2>三条不可退让的性质</h2>
 *
 * <ol>
 *   <li><b>解析失败必须抛，绝不能原样放行。</b>这是本类最重要的一条。
 *       如果「看不懂就放过」，那么让模型生成一段畸形 JSON 就绕过了整道防线——
 *       而提示注入恰好擅长让模型生成不该生成的东西。
 *       {@code ArgClamper} 的契约写的就是「参数非法且无法夹紧时抛
 *       {@code IllegalArgumentException}」。</li>
 *   <li><b>没有发生夹紧时，必须返回<b>原字符串</b>，而不是重新序列化的结果。</b>
 *       {@code GuardedToolCallback} 判定「是否夹紧」用的是
 *       {@code !args.equals(toolInput)}——<b>字符串比较</b>。
 *       解析再序列化会改掉空白与键序，于是每一次调用都会被记成
 *       {@code CLAMPED}，审计表里全是假的夹紧记录。
 *       <b>一条永远为真的审计断言等于没有断言。</b></li>
 *   <li><b>未知字段必须保留。</b>模型可能带上 {@code reason} 之类的字段，
 *       丢掉它们等于篡改这次调用的记录。所以夹紧时是<b>改树</b>而不是重建对象。</li>
 * </ol>
 *
 * <h2>刻意不做的事：这里不记审计</h2>
 *
 * <p>{@code ArgClamper} 的 javadoc 说「实现应在发生夹紧时记录审计」，
 * 但<b>本类不记</b>——{@code GuardedToolCallback} 已经在比较前后字符串后记了
 * {@code ToolAuditEvent.clamped(...)}。这里再记一次就会有两条审计行描述同一次夹紧，
 * 而两份记录迟早不一致。
 *
 * <p>更根本的原因是本类<b>拿不到</b> {@code ToolAuditContext}：
 * {@link ArgClamper#clamp} 的签名里没有它，而审计行的 {@code trace_id} 是 NOT NULL。
 * 硬记只能塞假值——那就是 C1 拒绝过的事。
 */
public final class JsonScaleArgsAdapter implements ArgClamper {

    /**
     * {@code FAIL_ON_TRAILING_TOKENS} <b>必须显式打开</b>。
     *
     * <p>Jackson 的 {@code readTree} 默认只读到第一个完整 JSON 值就停，
     * <b>后面还有什么一律不看</b>（该特性默认关闭）。于是
     * {@code {"service":"order","replicas":6} 后面这段垃圾} 会被判成合法参数，
     * 而垃圾部分被静默丢弃。
     *
     * <p>为什么这不能忍：夹紧器是安全边界，<b>边界上的「没看懂」必须失败，
     * 不能默认放过</b>。一段合法 JSON 后面跟着别的东西，
     * 要么是上游拼接出了 bug，要么是有人在试着夹带内容——
     * 两种都不该被安静地接受。
     */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private static final String FIELD_SERVICE = "service";
    private static final String FIELD_REPLICAS = "replicas";

    private final ScaleReplicasClamper clamper;

    public JsonScaleArgsAdapter(ReplicaStatePort replicaState,
                                ScaleReplicasClamper.PolicyProvider policyProvider) {
        this(new ScaleReplicasClamper(replicaState, policyProvider));
    }

    /** 供测试注入替身夹紧器。 */
    public JsonScaleArgsAdapter(ScaleReplicasClamper clamper) {
        if (clamper == null) {
            throw new IllegalArgumentException("clamper 不能为 null");
        }
        this.clamper = clamper;
    }

    @Override
    public String clamp(String toolName, String rawArgs) {
        // 不是扩容工具就原样放过：本适配层只懂 scale_replicas 的参数形状。
        // 注意这里返回的是**入参本身**，不是副本——见类注释第 2 条。
        if (!ScaleReplicasClamper.TOOL_NAME.equals(toolName)) {
            return rawArgs;
        }
        if (rawArgs == null || rawArgs.isBlank()) {
            throw new IllegalArgumentException(
                    ScaleReplicasClamper.TOOL_NAME + " 的参数不能为空：无法夹紧就不能放行");
        }

        JsonNode root;
        try {
            root = MAPPER.readTree(rawArgs);
        } catch (JsonProcessingException e) {
            // 【本类最重要的一条】畸形 JSON 绝不能原样放行。
            // 「看不懂就放过」等于给提示注入留了一个绕过整道防线的入口。
            throw new IllegalArgumentException(
                    ScaleReplicasClamper.TOOL_NAME + " 的参数不是合法 JSON，拒绝执行："
                            + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException(
                    ScaleReplicasClamper.TOOL_NAME + " 的参数必须是 JSON 对象");
        }

        ScaleReplicasClamper.ScaleRequest request =
                new ScaleReplicasClamper.ScaleRequest(
                        requireService(root), requireReplicas(root));

        ScaleReplicasClamper.ClampResult result = clamper.clamp(request);

        // 【没有夹紧就返回原字符串】重新序列化会改掉空白与键序，
        // 而 GuardedToolCallback 用字符串比较判定夹紧，
        // 那样每次调用都会留下一条假的 CLAMPED 审计。
        if (!result.clamped()) {
            return rawArgs;
        }

        // 改树而不是重建对象：模型带来的其它字段（reason 之类）必须留着，
        // 丢掉它们等于篡改这次调用的记录。
        ObjectNode out = (ObjectNode) root;
        out.put(FIELD_REPLICAS, result.target());
        try {
            return MAPPER.writeValueAsString(out);
        } catch (JsonProcessingException e) {
            // 走到这里说明我们刚读出来的树自己写不回去，属于内部错误。
            // 不能退回原参数——那等于放行一个未夹紧的值。
            throw new IllegalStateException("夹紧后的参数无法序列化", e);
        }
    }

    private static String requireService(JsonNode root) {
        JsonNode n = root.get(FIELD_SERVICE);
        if (n == null || !n.isTextual() || n.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "缺少合法的 " + FIELD_SERVICE + "：不知道要扩哪个服务就不能放行");
        }
        return n.asText();
    }

    /**
     * 取出副本数。
     *
     * <p><b>只接受整数。</b>{@code 8.0} 接受（它就是 8），
     * {@code 8.5} 与 {@code "8"} 都拒绝：
     * 半个人类读得懂的副本数是不存在的，而把字符串 {@code "8"} 当 8 用
     * 会让「参数类型由模型决定」，那正是夹紧要防的事。
     */
    private static int requireReplicas(JsonNode root) {
        JsonNode n = root.get(FIELD_REPLICAS);
        if (n == null || !n.isNumber()) {
            throw new IllegalArgumentException(
                    "缺少合法的 " + FIELD_REPLICAS + "：没有目标值就无法夹紧");
        }
        BigDecimal d = n.decimalValue();
        if (d.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(FIELD_REPLICAS + " 必须是整数，收到 " + d.toPlainString());
        }
        try {
            return d.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    FIELD_REPLICAS + " 超出 int 范围：" + d.toPlainString(), e);
        }
    }
}
