package com.oncall.eval;

import com.oncall.agent.query.IntentClassifier;
import com.oncall.agent.query.QueryUnderstanding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * L3 的<b>产出一半</b>：把 Golden Set 喂给分类器，把输出录下来。
 *
 * <p><b>这一半是非确定的，所以它不在 CI 里。</b>
 * 真模型每次跑都可能不一样，进了 CI 就是随机红——
 * 红了没人分得清是系统坏了还是模型今天不高兴。
 * 它应当在每晚 / 发版前跑，产物交给 {@link IntentJudge}。
 *
 * <p><b>但它对"用哪个模型"完全无知</b>：模型是 {@code IntentClassifier} 的构造参数，
 * 这里只负责跑与录。所以 CI 里用 {@code StubChatModel} 跑通整条链路
 * 和夜里用真模型跑，走的是<b>同一段代码</b>——
 * 这一点很重要，否则"CI 里跑过的"和"夜里跑的不是同一条路径"，
 * 那条链路上的 bug 会一直留到夜里才暴露。
 */
public final class IntentRunRunner {

    private IntentRunRunner() {
    }

    /**
     * @param classifier     被测分类器。评测跑批应当用
     *                       {@code QuerySettings.of(...)} 构造它，把配置<b>整批冻结</b>；
     *                       用 {@code ConfigBackedQuerySettings} 的话，
     *                       一次跑批跨了配置变更，录下来的输出就混了两组配置
     * @param model          模型标识，写进 provenance
     * @param recordedAtMillis 录制时刻，由调用方传入（不在这里读时钟）
     */
    public static IntentRunRecording run(IntentGoldenSet set, IntentClassifier classifier,
                                         String model, long recordedAtMillis) {
        Objects.requireNonNull(set, "set");
        Objects.requireNonNull(classifier, "classifier");

        List<RecordedIntent> results = new ArrayList<>();
        Set<String> promptVersions = new LinkedHashSet<>();
        for (IntentCase c : set.cases()) {
            QueryUnderstanding u = classifier.classify(c.text(), null);
            promptVersions.add(u.promptVersion());
            results.add(new RecordedIntent(c.id(), u.intent(), u.intentFromRule(),
                    u.llmIntent(), u.rewritten(), u.confidence(), u.modelOutputSuspect()));
        }

        // 一次跑批里出现两个 prompt 版本，说明中途有人热切换了生效版本。
        // 这时候所有指标都是两组 prompt 的混合平均值，
        // 而"混合平均值"看起来和正常数字一模一样——必须当场炸。
        if (promptVersions.size() != 1) {
            throw new IllegalStateException("一次跑批里出现了不止一个 prompt 版本：" + promptVersions
                    + "。这份录制会是两组 prompt 的混合平均值，无法归因，所以拒绝产出");
        }

        return new IntentRunRecording(
                new RunProvenance(model, promptVersions.iterator().next(),
                        classifier.settings().rewriteEnabled(),
                        classifier.settings().rewriteMinConfidence(),
                        recordedAtMillis),
                results);
    }
}
