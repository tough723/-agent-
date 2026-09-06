package com.oncall.eval;

import com.oncall.agent.query.Intent;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * {@code EXECUTE} 召回率门槛 —— <b>L3 里唯一现在就能进 CI 的判据</b>。
 *
 * <p>它能进 CI 的原因是：这个门槛只依赖规则层的正则，
 * <b>完全不经过 LLM</b>，所以它是确定的。其余 L3 指标（意图准确率、拒答率、
 * Recall@5、引用幻觉率）都要真模型，只能每晚跑，进 CI 就是随机红。
 *
 * <p><b>为什么门槛是 1.0 而不是 0.95</b>（《查询理解与知识表示设计》§4）：
 * 分类器的错误代价是不对称的。过度命中的代价是用户被多要求一次审批
 * ——可感知、可纠正；漏判的代价是一个高危操作绕过闸门——不可感知、不可挽回。
 * 所以召回率不设容忍度，而<b>过度命中不计入门槛</b>。
 *
 * <p><b>关于"通过"的含义，必须说清楚：</b>
 * 在 N 条标注上召回率 1.0，说的是"这 N 条不漏"，
 * <b>不是</b>"对真实用户的说法召回率 1.0"。后者无法证明，只能逼近：
 * <ul>
 *   <li>这个集子给出的是召回率的<b>下界</b>，不是真值；</li>
 *   <li>把正则调到刚好通过自己写的集子，就是<b>对测试集过拟合</b>——
 *       所以集子必须持续从真实的漏判里长出来：线上每一次"该拦没拦"
 *       都要作为一条新用例进来，让它再也不会漏第二次。</li>
 * </ul>
 * 这条门槛真正的价值是<b>回归保护</b>：已知的说法一旦被覆盖，就永远不许再漏。
 */
public final class ExecuteRecallGate {

    /** 硬门槛，不留容忍度。理由见类注释。 */
    public static final double REQUIRED_RECALL = 1.0;

    private ExecuteRecallGate() {
    }

    /**
     * @param rule 规则层判定，通常是 {@code IntentClassifier.EXECUTE_INTENT} 的
     *             {@code matcher(text).find()}。抽成参数是为了让同一个门槛
     *             能评估不同的正则，而不必改门槛本身
     */
    public static Result check(IntentGoldenSet set, Predicate<String> rule) {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(rule, "rule");

        List<IntentCase> expected = set.withExpected(Intent.EXECUTE);
        List<IntentCase> misses = expected.stream()
                .filter(c -> !rule.test(c.text()))
                .toList();
        List<IntentCase> overHits = set.cases().stream()
                .filter(c -> c.expect() != Intent.EXECUTE)
                .filter(c -> rule.test(c.text()))
                .toList();

        double recall = expected.isEmpty() ? 0.0
                : (double) (expected.size() - misses.size()) / expected.size();
        // 分母为 0 时必须判为不通过。写成 misses.isEmpty() 会让一个
        // 「一条 EXECUTE 用例都没有」的集子直接拿满分——
        // 而"标注集被清空"恰好是评测体系最容易悄悄发生的事。
        boolean passed = !expected.isEmpty() && misses.isEmpty();
        return new Result(expected.size(), misses, recall, passed, overHits);
    }

    /**
     * @param total    标注为 {@code EXECUTE} 的用例数，即门槛的分母
     * @param misses   规则层没命中的那些。<b>每一条都是一个绕过闸门的口子</b>
     * @param recall   召回率
     * @param passed   是否达到 {@link #REQUIRED_RECALL}
     * @param overHits 语义上不是操作请求、却被规则层命中的用例。
     *                 <b>不计入门槛</b>，但这个数太高说明用户会被无谓打断，
     *                 而频繁被打断的人会开始闭眼点同意——那会掏空闸门本身的价值
     */
    public record Result(int total, List<IntentCase> misses, double recall,
                         boolean passed, List<IntentCase> overHits) {

        public Result {
            misses = List.copyOf(misses);
            overHits = List.copyOf(overHits);
        }

        /** 过度命中率，分母是全部非 EXECUTE 用例。 */
        public double overHitRate(int nonExecuteCases) {
            return nonExecuteCases == 0 ? 0.0 : (double) overHits.size() / nonExecuteCases;
        }

        /**
         * 失败详情。<b>逐条列出漏判</b>，而不是只给一个比例：
         * "召回率 0.40" 让人不知道从哪下手，
         * "这 15 条漏了，它们分别是这么说的" 才是能行动的信息。
         */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append("EXECUTE 召回率 ").append(String.format("%.3f", recall))
                    .append("（").append(total - misses.size()).append('/').append(total)
                    .append("），门槛 ").append(REQUIRED_RECALL);
            if (!misses.isEmpty()) {
                sb.append("。漏判 ").append(misses.size()).append(" 条，"
                        + "每一条都是一个能绕过审批闸门的口子：");
                for (IntentCase m : misses) {
                    sb.append("\n  ").append(m.id())
                            .append(" [").append(m.group()).append("] ")
                            .append(m.text());
                }
            }
            if (!overHits.isEmpty()) {
                sb.append("\n过度命中 ").append(overHits.size())
                        .append(" 条（不计入门槛，但太高说明用户会被无谓打断）：");
                for (IntentCase o : overHits) {
                    sb.append("\n  ").append(o.id())
                            .append(" 期望=").append(o.expect()).append(' ')
                            .append(o.text());
                }
            }
            return sb.toString();
        }
    }
}
