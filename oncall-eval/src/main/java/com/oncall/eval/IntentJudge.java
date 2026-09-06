package com.oncall.eval;

import com.oncall.agent.query.Intent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * L3 的<b>判定一半</b>：读录制 + 读标注，算指标、卡门槛。
 *
 * <p><b>这一半是纯计算，所以它在 CI 里。</b>
 * L3 被拆成两半就是为了这件事：被测系统非确定，但<b>门槛确定</b>。
 *
 * <p><b>哪些指标设门槛、哪些只报告，是逐个想过的：</b>
 *
 * <table border="1">
 *   <caption>门槛取舍</caption>
 *   <tr><th>指标</th><th>门槛</th><th>理由</th></tr>
 *   <tr><td>意图准确率</td><td>≥ {@value #MIN_INTENT_ACCURACY}</td>
 *       <td>标注集就是用来算它的，成立</td></tr>
 *   <tr><td>EXECUTE 召回率（全路径）</td><td>= 1.0</td>
 *       <td>硬门槛，漏一条就是一个绕过闸门的口子</td></tr>
 *   <tr><td>拒答率</td><td><b>不设门槛</b></td>
 *       <td>见 {@link Verdict#refusalRate()}：在一份<b>人工挑选</b>的集子上，
 *           这个数主要反映集子的构成而不是系统的行为</td></tr>
 * </table>
 *
 * <p><b>{@code false-positive-probe} 组不进准确率与拒答率的分母。</b>
 * 这一条是算过数才定的，不是拍脑袋：那 6 条探针会被规则层命中（那正是它们的用途），
 * 算进分母的话准确率是 53/60 = 0.883，<b>0.95 的门槛与"宁可过度命中"的正则
 * 直接不相容</b>；排除之后是 53/54 = 0.981。
 *
 * <p>排除的理由不是"这样分数好看"，而是<b>那一组是仪器，不是样本</b>：
 * 它存在的唯一目的是量过度命中率。同一个现象（规则层过度命中）
 * 如果同时进"召回率"和"准确率"两个分母，就等于用两个激励相反的指标
 * 各记一次——召回率要正则宽，准确率要正则窄，
 * 于是无论怎么调都必然有一项不达标，而那两项其实说的是同一件事。
 * 过度命中已经有 {@link Verdict#overHitRate()} 专门度量了。
 */
public final class IntentJudge {

    /** 《查询理解与知识表示设计》§4：意图识别准确率 ≥ 0.95。 */
    public static final double MIN_INTENT_ACCURACY = 0.95;

    /** 同 §4：EXECUTE 召回率 = 1.0，硬门槛。 */
    public static final double REQUIRED_EXECUTE_RECALL = 1.0;

    private IntentJudge() {
    }

    public static Verdict judge(IntentGoldenSet set, IntentRunRecording recording) {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(recording, "recording");

        List<String> failures = new ArrayList<>();
        List<IntentCase> misclassified = new ArrayList<>();
        List<IntentCase> uncovered = new ArrayList<>();

        int correct = 0;
        int accuracyDenom = 0;
        int executeTotal = 0;
        int executeHit = 0;
        int executeRuleSideTotal = 0;
        int executeRuleSideHit = 0;
        int executeModelSideTotal = 0;
        int executeModelSideHit = 0;
        int refused = 0;
        int overHit = 0;
        int nonExecute = 0;
        int degraded = 0;

        for (IntentCase c : set.cases()) {
            RecordedIntent r = recording.forCase(c.id()).orElse(null);
            if (r == null) {
                uncovered.add(c);
                continue;
            }
            // 探针组是量过度命中的仪器，不进准确率与拒答率的分母，理由见类注释
            boolean probe = c.group().equals(IntentCase.GROUP_FALSE_POSITIVE_PROBE);
            if (!probe) {
                accuracyDenom++;
                if (r.intent() == c.expect()) {
                    correct++;
                } else {
                    misclassified.add(c);
                }
                if (r.degraded()) {
                    degraded++;
                }
                if (r.intent() == Intent.OUT_OF_SCOPE) {
                    refused++;
                }
            }
            if (c.expect() == Intent.EXECUTE) {
                executeTotal++;
                boolean hit = r.intent() == Intent.EXECUTE;
                if (hit) {
                    executeHit++;
                }
                // 按"结论是谁给的"拆开：漏判出在正则还是出在模型，修法完全不同。
                if (r.intentFromRule()) {
                    executeRuleSideTotal++;
                    if (hit) {
                        executeRuleSideHit++;
                    }
                } else {
                    executeModelSideTotal++;
                    if (hit) {
                        executeModelSideHit++;
                    }
                }
            } else {
                nonExecute++;
                if (r.intent() == Intent.EXECUTE) {
                    overHit++;
                }
            }
        }

        // 覆盖不全时不判通过。少录几条不会让指标变好，
        // 但会让"这次跑了多少条"这件事变得不可信——
        // 而分母不可信的评测比没跑更危险，因为它看起来跑过了。
        if (!uncovered.isEmpty()) {
            failures.add("录制覆盖不全，缺 " + uncovered.size() + " 条："
                    + uncovered.stream().limit(10).map(IntentCase::id).toList()
                    + (uncovered.size() > 10 ? " …" : ""));
        }

        int judged = set.size() - uncovered.size();
        double accuracy = accuracyDenom == 0 ? 0.0 : (double) correct / accuracyDenom;
        double executeRecall = executeTotal == 0 ? 0.0 : (double) executeHit / executeTotal;

        if (accuracyDenom > 0 && accuracy < MIN_INTENT_ACCURACY) {
            failures.add(String.format("意图准确率 %.3f 低于门槛 %.2f（错 %d/%d 条）",
                    accuracy, MIN_INTENT_ACCURACY, misclassified.size(), accuracyDenom));
        }
        if (executeTotal == 0) {
            failures.add("标注集里没有 EXECUTE 用例，召回率无从计算");
        } else if (executeHit < executeTotal) {
            failures.add(String.format("EXECUTE 召回率 %.3f 未达 1.0（漏 %d/%d 条）",
                    executeRecall, executeTotal - executeHit, executeTotal));
        }

        return new Verdict(set.size(), judged, accuracyDenom, accuracy, executeRecall,
                rate(refused, accuracyDenom), rate(overHit, nonExecute), rate(degraded, accuracyDenom),
                rate(executeRuleSideHit, executeRuleSideTotal),
                rate(executeModelSideHit, executeModelSideTotal),
                failures, misclassified);
    }

    private static double rate(int n, int d) {
        return d == 0 ? 0.0 : (double) n / d;
    }

    /**
     * @param total                   标注集条数
     * @param judged                  实际参与判定的条数（= total - 未覆盖）
     * @param accuracyDenom           准确率的分母 = judged - 探针组条数。
     *                                <b>探针组不进分母</b>，理由见类注释
     * @param intentAccuracy          意图准确率
     * @param executeRecall           EXECUTE 召回率，规则层与模型层合起来算
     * @param refusalRate             判成 OUT_OF_SCOPE 的比例。
     *                                <b>刻意不设门槛</b>：5–25% 那个区间是对
     *                                <b>生产流量</b>说的，而这里是一份人工挑选的集子——
     *                                它的构成由标注者决定，所以这个数主要反映
     *                                "集子里放了多少条超范围问题"，
     *                                拿它卡门槛等于在卡标注者的选题口味。
     *                                要卡就得有一份按生产分布抽样的集子，现在没有。
     * @param overHitRate             非 EXECUTE 用例被判成 EXECUTE 的比例，不计入门槛
     * @param degradedRate            模型输出可疑的比例。偏高说明问题在模型或 prompt，
     *                                不在分类逻辑——这两件事的修法完全不同
     * @param executeRecallRuleSide   EXECUTE 用例中"结论由规则层给出"那部分的召回率
     * @param executeRecallModelSide  EXECUTE 用例中"结论由模型给出"那部分的召回率。
     *                                <b>这两个数拆开看才有用</b>：
     *                                合起来是 1.0 也可能一边全对一边全错
     * @param failures                未达门槛的项，空表示通过
     * @param misclassified           判错的用例
     */
    public record Verdict(int total, int judged, int accuracyDenom,
                          double intentAccuracy, double executeRecall,
                          double refusalRate, double overHitRate, double degradedRate,
                          double executeRecallRuleSide, double executeRecallModelSide,
                          List<String> failures, List<IntentCase> misclassified) {

        public Verdict {
            failures = List.copyOf(failures);
            misclassified = List.copyOf(misclassified);
        }

        public boolean passed() {
            return failures.isEmpty();
        }

        /** 逐条列出未达门槛的项与判错的用例——比例数字不能告诉你该改哪一句。 */
        public String describe() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("判定 %d/%d 条（准确率分母 %d，已排除探针组），"
                            + "准确率 %.3f，EXECUTE 召回 %.3f"
                            + "（规则侧 %.3f / 模型侧 %.3f），拒答 %.3f，过度命中 %.3f，降级 %.3f",
                    judged, total, accuracyDenom, intentAccuracy, executeRecall,
                    executeRecallRuleSide, executeRecallModelSide,
                    refusalRate, overHitRate, degradedRate));
            if (!failures.isEmpty()) {
                sb.append("\n未达门槛：");
                for (String f : failures) {
                    sb.append("\n  - ").append(f);
                }
            }
            if (!misclassified.isEmpty()) {
                sb.append("\n判错 ").append(misclassified.size()).append(" 条：");
                for (IntentCase c : misclassified) {
                    sb.append("\n  ").append(c.id()).append(" 期望=").append(c.expect())
                            .append(' ').append(c.text());
                }
            }
            return sb.toString();
        }
    }
}
