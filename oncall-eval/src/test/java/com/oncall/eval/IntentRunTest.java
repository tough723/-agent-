package com.oncall.eval;

import com.oncall.agent.prompt.ActiveVersionSource;
import com.oncall.agent.prompt.PromptRegistry;
import com.oncall.agent.query.Intent;
import com.oncall.agent.query.IntentClassifier;
import com.oncall.agent.query.QuerySettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L3 的两半接在一起跑：产出一半（{@link IntentRunRunner}）→ 录制 →
 * 判定一半（{@link IntentJudge}）。
 *
 * <p><b>为什么不复用 {@code oncall-agent-core} 的 {@code StubChatModel}</b>：
 * 它在那个模块的 {@code src/test/java} 里，跨模块要用就得发 test-jar，
 * 而 test-jar 是 {@code package} 阶段的产物、CI 跑的是 {@code mvn test}，
 * 拿不到。这里只需要"按顺序返回脚本文本"这一件事，
 * 于是就地写一个 lambda——{@code ChatModel} 只有一个抽象方法。
 * {@code StubChatModel} 的失败注入、调用计数那些能力这里用不上。
 */
@DisplayName("IntentRun：产出一半 → 录制 → 判定一半")
class IntentRunTest {

    private static final IntentGoldenSet SET =
            IntentGoldenSet.load("golden-set/intent/intent-v1.yaml");

    private static final QuerySettings FROZEN = QuerySettings.of(true, 0.7);

    // ------------------------------------------------------------------ 端到端

    @Test
    @DisplayName("★ 全链路：60 条真标注跑一遍、录下来、判一遍，两个门槛都过")
    void endToEndOverTheRealGoldenSet() {
        // 脚本按"每条都答对"生成，所以准确率与召回率都应当是 1.0。
        // 这条用例验的不是模型好不好，而是**这条链路本身能跑通**：
        // runner 调 classifier、映射成 RecordedIntent、写 provenance、
        // judge 读标注对齐、算指标、下结论。
        String[] script = SET.cases().stream()
                .map(c -> json(c.expect().name(), 0.9))
                .toArray(String[]::new);
        IntentClassifier classifier = classifier(scripted(script), FROZEN);

        IntentRunRecording rec = IntentRunRunner.run(SET, classifier, "stub-model", 1_700_000_000_000L);
        IntentJudge.Verdict v = IntentJudge.judge(SET, rec);

        assertThat(v.failures()).as(v.describe()).isEmpty();
        assertThat(v.passed()).isTrue();
        assertThat(v.judged()).isEqualTo(60);
        // 准确率分母是 54 而不是 60：探针组那 6 条会被规则层命中（那正是它们的用途），
        // 算进分母的话准确率是 53/60 = 0.883，0.95 的门槛与保守正则直接不相容。
        // 这里唯一被判错的是 INT-044「把 query.rewrite-enabled 关掉」——
        // 它标注是 CONFIGURE，但规则层的「关掉」会把它抢成 EXECUTE。
        assertThat(v.accuracyDenom()).isEqualTo(54);
        assertThat(v.intentAccuracy()).isEqualTo(53.0 / 54.0);
        assertThat(v.executeRecall()).isEqualTo(1.0);
        assertThat(v.overHitRate()).as("过度命中仍然照实报出来").isGreaterThan(0.0);
    }

    @Test
    @DisplayName("provenance 取自分类器实际用的设置，不是另外读的")
    void provenanceComesFromTheClassifierSettings() {
        IntentClassifier classifier = classifier(scripted(allCorrect()), QuerySettings.of(false, 0.35));

        IntentRunRecording rec = IntentRunRunner.run(SET, classifier, "deepseek-v4-flash", 42L);

        assertThat(rec.provenance().model()).isEqualTo("deepseek-v4-flash");
        assertThat(rec.provenance().promptVersion()).isEqualTo("v1");
        assertThat(rec.provenance().rewriteEnabled()).isFalse();
        assertThat(rec.provenance().rewriteMinConfidence()).isEqualTo(0.35);
        assertThat(rec.provenance().recordedAtMillis()).isEqualTo(42L);
        assertThat(rec.size()).isEqualTo(60);
    }

    @Test
    @DisplayName("★ 一次跑批里出现两个 prompt 版本 → 拒绝产出")
    void mixedPromptVersionsAreRejected() {
        // 跑到一半有人热切换了生效版本。这时候所有指标都是两组 prompt 的
        // 混合平均值，而"混合平均值"看起来和正常数字一模一样——必须当场炸。
        AtomicInteger calls = new AtomicInteger();
        ActiveVersionSource flipping = name -> calls.getAndIncrement() < 30 ? "v1" : "v2";
        PromptRegistry prompts = PromptRegistry.fromClasspath(
                List.of("intent-classify"), flipping);
        IntentClassifier classifier = new IntentClassifier(
                scripted(allCorrect()), prompts, FROZEN);

        assertThatThrownBy(() -> IntentRunRunner.run(SET, classifier, "m", 1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("不止一个 prompt 版本")
                .hasMessageContaining("v1").hasMessageContaining("v2");
    }

    @Test
    @DisplayName("录制经过 YAML 往返后，判定结论不变")
    void verdictSurvivesTheYamlRoundTrip() {
        IntentRunRecording rec = IntentRunRunner.run(
                SET, classifier(scripted(allCorrect()), FROZEN), "m", 1L);

        IntentJudge.Verdict before = IntentJudge.judge(SET, rec);
        IntentJudge.Verdict after = IntentJudge.judge(SET, IntentRunRecording.fromYaml(rec.toYaml()));

        assertThat(after.intentAccuracy()).isEqualTo(before.intentAccuracy());
        assertThat(after.executeRecall()).isEqualTo(before.executeRecall());
        assertThat(after.passed()).isEqualTo(before.passed());
    }

    // ------------------------------------------------------------------ 判定门槛

    @Test
    @DisplayName("录制覆盖不全 → 不判通过（分母不可信比没跑更危险）")
    void incompleteRecordingFailsTheJudge() {
        IntentGoldenSet small = IntentGoldenSet.load("golden-set/intent/refusal-heavy.yaml");
        // 只录 11 条里的 9 条
        IntentRunRecording rec = new IntentRunRecording(prov(),
                small.cases().stream().limit(9).map(IntentRunTest::correct).toList());

        IntentJudge.Verdict v = IntentJudge.judge(small, rec);

        assertThat(v.passed()).isFalse();
        assertThat(v.failures()).anyMatch(f -> f.contains("覆盖不全"));
        assertThat(v.judged()).isEqualTo(9);
    }

    @Test
    @DisplayName("准确率低于 0.95 → 不通过，并逐条列出判错的用例")
    void accuracyBelowThresholdFails() {
        // 60 条里错 4 条 = 0.933 < 0.95
        IntentRunRecording rec = recording(SET, c ->
                List.of("INT-026", "INT-032", "INT-037", "INT-045").contains(c.id())
                        ? as(c, Intent.CHITCHAT)
                        : correct(c));

        IntentJudge.Verdict v = IntentJudge.judge(SET, rec);

        assertThat(v.passed()).isFalse();
        assertThat(v.intentAccuracy()).isEqualTo(50.0 / 54.0);
        assertThat(v.intentAccuracy()).isLessThan(IntentJudge.MIN_INTENT_ACCURACY);
        assertThat(v.failures()).anyMatch(f -> f.contains("意图准确率"));
        // 召回率仍然是 1.0 —— 准确率这一项独立地把它拦住了
        assertThat(v.executeRecall()).isEqualTo(1.0);
        assertThat(v.misclassified()).extracting(IntentCase::id)
                .containsExactly("INT-026", "INT-032", "INT-037", "INT-045");
        assertThat(v.describe()).contains("INT-026").contains("HighMemoryUsage");
    }

    @Test
    @DisplayName("★ EXECUTE 召回率不足 1.0 → 不通过")
    void executeRecallBelowOneFails() {
        // 只漏一条：把 INT-020（"把这个版本撤下来"）判成 EXPLAIN_ALERT
        IntentRunRecording rec = recording(SET, c ->
                c.id().equals("INT-020") ? as(c, Intent.EXPLAIN_ALERT) : correct(c));

        IntentJudge.Verdict v = IntentJudge.judge(SET, rec);

        assertThat(v.passed()).isFalse();
        assertThat(v.executeRecall()).isEqualTo(24.0 / 25.0);
        assertThat(v.failures()).anyMatch(f -> f.contains("EXECUTE 召回率"));
        // 准确率 53/54 = 0.981 仍然达标——**只有召回率这一项把它拦住了**，
        // 这正是"错误代价不对称"的意思：整体看着挺好，但那一条会绕过闸门。
        assertThat(v.intentAccuracy()).isGreaterThan(IntentJudge.MIN_INTENT_ACCURACY);
    }

    @Test
    @DisplayName("★ 召回率按「结论是规则给的还是模型给的」拆开报")
    void recallIsBrokenOutByWhoDecided() {
        // 显式动词那 10 条由规则层判、全对；漏判专项那 15 条由模型判、错 1 条。
        // 合起来是 0.96，但拆开看就知道：**正则没问题，问题在模型**。
        // 这两个数合在一起报，等于把该修哪儿的信息丢掉了。
        IntentRunRecording rec = recording(SET, c -> {
            if (c.expect() != Intent.EXECUTE) {
                return correct(c);
            }
            boolean ruleSide = c.group().equals("execute-explicit");
            boolean miss = c.id().equals("INT-020");
            return new RecordedIntent(c.id(),
                    miss ? Intent.EXPLAIN_ALERT : Intent.EXECUTE,
                    ruleSide, miss ? Intent.EXPLAIN_ALERT : Intent.EXECUTE,
                    false, 0.9, false);
        });

        IntentJudge.Verdict v = IntentJudge.judge(SET, rec);

        assertThat(v.executeRecallRuleSide()).isEqualTo(1.0);
        assertThat(v.executeRecallModelSide()).isEqualTo(14.0 / 15.0);
        assertThat(v.executeRecall()).isEqualTo(24.0 / 25.0);
    }

    @Test
    @DisplayName("★ 拒答率越界也不拦：5–25% 那个区间是对生产流量说的，不是对人工挑的集子")
    void refusalRateIsReportedButNeverGated() {
        IntentGoldenSet heavy = IntentGoldenSet.load("golden-set/intent/refusal-heavy.yaml");
        // 全对，于是拒答率 = 6/10 = 0.60，远在 5–25% 之外
        IntentRunRecording rec = recording(heavy, IntentRunTest::correct);

        IntentJudge.Verdict v = IntentJudge.judge(heavy, rec);

        assertThat(v.refusalRate()).isEqualTo(6.0 / 11.0);
        assertThat(v.refusalRate()).as("0.545 远在 5–25% 之外").isGreaterThan(0.25);
        assertThat(v.passed())
                .as("在一份人工挑选的集子上，拒答率主要反映集子的构成而不是系统的行为；"
                        + "拿它卡门槛等于在卡标注者的选题口味")
                .isTrue();
        assertThat(v.describe()).contains("拒答 0.545");
    }

    @Test
    @DisplayName("降级率会被报出来：偏高说明问题在模型或 prompt，不在分类逻辑")
    void degradedRateIsReported() {
        IntentRunRecording rec = recording(SET, c ->
                c.id().startsWith("INT-0") && c.id().endsWith("1")
                        ? new RecordedIntent(c.id(), c.expect(), false, c.expect(), false, 0.9, true)
                        : correct(c));

        IntentJudge.Verdict v = IntentJudge.judge(SET, rec);

        assertThat(v.degradedRate()).isEqualTo(6.0 / 54.0);
        assertThat(v.describe()).contains("降级");
    }

    // ------------------------------------------------------------------ helpers

    private static String json(String intent, double confidence) {
        return "{\"intent\":\"" + intent + "\",\"standaloneQuery\":\"q\",\"rewritten\":false,"
                + "\"resolvedEntities\":[],\"confidence\":" + confidence + "}";
    }

    private static String[] allCorrect() {
        return SET.cases().stream().map(c -> json(c.expect().name(), 0.9)).toArray(String[]::new);
    }

    /** 按顺序吐脚本的 ChatModel。{@code ChatModel} 只有一个抽象方法，所以能写成 lambda。 */
    private static ChatModel scripted(String... responses) {
        Deque<String> queue = new ArrayDeque<>(List.of(responses));
        return prompt -> new ChatResponse(List.of(new Generation(
                new AssistantMessage(queue.isEmpty() ? json("CHITCHAT", 0.1) : queue.pop()))));
    }

    private static IntentClassifier classifier(ChatModel model, QuerySettings settings) {
        PromptRegistry prompts = PromptRegistry.fromClasspath(
                List.of("intent-classify"), ActiveVersionSource.fixed(java.util.Map.of("intent-classify", "v1")));
        return new IntentClassifier(model, prompts, settings);
    }

    private static RunProvenance prov() {
        return new RunProvenance("m", "v1", true, 0.7, 1L);
    }

    private static RecordedIntent correct(IntentCase c) {
        return new RecordedIntent(c.id(), c.expect(), false, c.expect(), false, 0.9, false);
    }

    private static RecordedIntent as(IntentCase c, Intent intent) {
        return new RecordedIntent(c.id(), intent, false, intent, false, 0.9, false);
    }

    private static IntentRunRecording recording(IntentGoldenSet set, Function<IntentCase, RecordedIntent> f) {
        return new IntentRunRecording(prov(), set.cases().stream().map(f).toList());
    }
}
