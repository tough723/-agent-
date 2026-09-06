package com.oncall.agent.query;

import java.util.List;
import java.util.Objects;

/**
 * 一次查询理解的完整结果——<b>它同时是这个阶段的可观测面</b>。
 *
 * <p>字段多不是设计失误：查询理解是整条链路里"错了但看不出来"最集中的一段
 * （改写把原意改歪、指代消解到错的服务、意图误判），
 * 而它的每一个失败模式都只会表现为"回答变差"，不会报错。
 * 所以这里刻意把<b>决策</b>和<b>决策的来源</b>一起返回，
 * 让调用方与日志能回答"当时为什么这么判"。
 *
 * @param intent                 最终意图，路由依据
 * @param intentFromRule         这个意图是不是<b>规则层</b>定的。
 *                               为 {@code true} 时下游<b>不得</b>软化它——
 *                               规则层命中意味着必须走审批闸门
 * @param llmIntent              模型自报的意图，可能为 {@code null}（标签在闭集之外）。
 *                               留着它是为了度量"模型与规则层的分歧率"，
 *                               那是调正则的唯一依据
 * @param standaloneQuery        用于检索的查询。未改写时等于原句
 * @param rewritten              是否采用了模型的改写。为 {@code false} 时
 *                               检索层应当<b>只用原句</b>
 * @param resolvedEntities       指代消解结果，<b>必须回显给用户</b>（护栏 1）
 * @param confidence             模型自报置信度，{@code [0,1]}
 * @param promptVersion          实际用到的 prompt 版本，<b>必须写进
 *                               {@code llm_call_log.prompt_version}</b>
 * @param degradeReason          <b>模型输出</b>的问题：调用失败、返回空、无法解析，
 *                               或意图标签落在闭集之外。正常时为 {@code null}。
 *                               <b>注意它不等于"这次结果不可用"</b>——
 *                               当 {@code intentFromRule} 为 {@code true} 时，
 *                               意图是规则层定的，模型的问题没有影响结论。
 *                               但它仍然是调 prompt 的输入，所以照样记下来：
 *                               "没影响结果"恰恰是这类退化最容易被忽略的原因
 * @param rewriteSuppressedReason 改写被护栏挡下的原因；采用了改写时为 {@code null}
 */
public record QueryUnderstanding(
        Intent intent,
        boolean intentFromRule,
        Intent llmIntent,
        String standaloneQuery,
        boolean rewritten,
        List<ResolvedEntity> resolvedEntities,
        double confidence,
        String promptVersion,
        String degradeReason,
        String rewriteSuppressedReason) {

    public QueryUnderstanding {
        Objects.requireNonNull(intent, "intent");
        if (standaloneQuery == null || standaloneQuery.isBlank()) {
            throw new IllegalArgumentException("standaloneQuery 不能为空：没有可检索的查询，"
                    + "这个结果就没有意义");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence 必须落在 [0,1]，收到 " + confidence
                    + "。模型给出界外的置信度说明它没有遵守输出契约，"
                    + "这种输出不能当成\"很有把握\"来用");
        }
        resolvedEntities = resolvedEntities == null ? List.of() : List.copyOf(resolvedEntities);
    }

    /**
     * 降级结果：模型调用失败、返回空、或输出无法解析为合法 JSON。
     *
     * <p><b>降级不是重试，也不是猜。</b>
     * 意图兜底成 {@link Intent#OUT_OF_SCOPE}（拒绝），因为猜一个意图
     * 比拒绝更危险；<b>除非规则层已经确定性地判出了 {@code EXECUTE}</b>——
     * 那种情况下即使模型完全不可用，请求也必须进审批闸门。
     *
     * <p>改写一律不采用，检索用原句：宁可召回差，不要改歪。
     */
    public static QueryUnderstanding degraded(Intent intent, boolean intentFromRule,
                                              String question, String promptVersion, String reason) {
        Objects.requireNonNull(reason, "reason");
        return new QueryUnderstanding(intent, intentFromRule, null, question, false,
                List.of(), 0.0, promptVersion, reason, "模型输出不可用，未改写");
    }

    /**
     * 模型输出是否有问题。
     *
     * <p><b>刻意不叫 {@code isUsable()} 的反面</b>：{@code intentFromRule} 为 true 时，
     * 即使这里返回 {@code true}，意图依然是可用的（它不是模型给的）。
     * 把两件事合成一个布尔值，会让"模型在退化"这个信号被规则层的正确掩盖掉。
     */
    public boolean modelOutputSuspect() {
        return degradeReason != null;
    }

    /**
     * 模型与规则层的意图是否分歧。
     *
     * <p>这个比例是<b>调 {@code EXECUTE} 正则的唯一依据</b>：
     * 分歧率高说明正则在过度命中（把只读请求也判成 EXECUTE，用户会被无谓地要求审批）；
     * 而"规则没命中但模型说是 EXECUTE"的那部分，
     * 是正则漏判的候选样本——**那是召回率的缺口，必须逐条看**。
     */
    public boolean intentDisagreesWithRule() {
        return intentFromRule && llmIntent != null && llmIntent != Intent.EXECUTE;
    }
}
