package com.oncall.eval;

import com.oncall.agent.query.Intent;
import com.oncall.agent.query.IntentClassifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code EXECUTE} 召回率门槛。
 *
 * <p><b>这是 L3 里唯一进 CI 的判据</b>，因为它只依赖规则层的正则、
 * 完全不经过 LLM，所以是确定的。其余 L3 指标要真模型，进 CI 就是随机红。
 *
 * <p>这里最重要的一条不是"通过"，而是<b>「这个门槛能失败」</b>：
 * 一个不会红的门槛等于没有门槛。所以专门有一条用例拿最初那版
 * 9 个动词的正则去跑，断言它<b>必须</b>被判不通过——
 * 那既是非空转证明，也把"当初实测召回率只有 0.400"这个事实永久钉在测试里。
 */
@DisplayName("ExecuteRecallGate：EXECUTE 召回率硬门槛")
class ExecuteRecallGateTest {

    private static final IntentGoldenSet SET =
            IntentGoldenSet.load("golden-set/intent/intent-v1.yaml");

    /** B3 最初那版正则。留着它是为了证明门槛能红。 */
    private static final Pattern ORIGINAL_NINE_VERBS = Pattern.compile(
            "(重启|扩容|缩容|回滚|下线|切流|删除|清理|执行).{0,20}(服务|实例|pod|副本|节点)?");

    private static boolean hits(Pattern p, String text) {
        return p.matcher(text).find();
    }

    // ------------------------------------------------------------------ 门槛

    @Test
    @DisplayName("★ 硬门槛：当前正则在整个标注集上 EXECUTE 召回率 = 1.0")
    void shippedRegexMeetsTheHardGate() {
        ExecuteRecallGate.Result r = ExecuteRecallGate.check(SET,
                text -> hits(IntentClassifier.EXECUTE_INTENT, text));

        // describe() 会逐条列出漏判，所以失败时能直接看到该改哪一句
        assertThat(r.passed()).as(r.describe()).isTrue();
        assertThat(r.recall()).isEqualTo(ExecuteRecallGate.REQUIRED_RECALL);
        assertThat(r.misses()).isEmpty();
        assertThat(r.total()).as("门槛的分母不能是 0，否则这条断言没有意义").isGreaterThan(0);
    }

    @Test
    @DisplayName("★ 非空转证明：最初那版 9 个动词的正则必须被判不通过")
    void theOriginalRegexFailsTheGate() {
        ExecuteRecallGate.Result r = ExecuteRecallGate.check(SET,
                text -> hits(ORIGINAL_NINE_VERBS, text));

        // 这条断言记录的是一个实测事实，不是假设：
        // 在补标注集之前，那个"确定性安全门槛"对大多数真实说法是失效的，
        // 而这件事在没有标注集的时候完全看不出来。
        assertThat(r.passed()).as("9 个动词的正则应当被门槛拒绝").isFalse();
        assertThat(r.total()).isEqualTo(25);
        assertThat(r.misses()).hasSize(15);
        assertThat(r.recall()).isEqualTo(0.4);
        assertThat(r.misses())
                .as("漏判必须集中在漏判专项组——显式动词那组本来就是照着正则写的")
                .allSatisfy(m -> assertThat(m.group()).isEqualTo(IntentCase.GROUP_EXECUTE_PARAPHRASE));
    }

    @Test
    @DisplayName("一个「什么都不命中」的规则必须被拒绝，且分母照实报告")
    void aRuleThatNeverFiresIsRejected() {
        ExecuteRecallGate.Result r = ExecuteRecallGate.check(SET, text -> false);

        assertThat(r.passed()).isFalse();
        assertThat(r.misses()).hasSize(r.total());
        assertThat(r.recall()).isZero();
    }

    @Test
    @DisplayName("分母为 0 时判为不通过——标注集被清空不能变成满分")
    void anEmptyExecuteSetDoesNotPass() {
        IntentGoldenSet noExecute = IntentGoldenSet.load("golden-set/intent/no-execute.yaml");

        ExecuteRecallGate.Result r = ExecuteRecallGate.check(noExecute, text -> false);

        assertThat(r.total()).isZero();
        assertThat(r.misses()).isEmpty();
        assertThat(r.passed())
                .as("misses 为空但分母为 0，绝不能算通过")
                .isFalse();
    }

    // ------------------------------------------------------------------ 过度命中

    @Test
    @DisplayName("过度命中会被报告，但不影响门槛结论")
    void overHitsAreReportedButDoNotFailTheGate() {
        ExecuteRecallGate.Result r = ExecuteRecallGate.check(SET,
                text -> hits(IntentClassifier.EXECUTE_INTENT, text));

        assertThat(r.passed()).isTrue();
        // 探针组的 6 条语义上都不是操作请求，却都会被命中——这是刻意的代价：
        // 过度命中只多一次审批，漏判会绕过闸门，两者不对称。
        assertThat(r.overHits())
                .extracting(IntentCase::id)
                .contains("INT-055", "INT-056", "INT-057", "INT-058", "INT-059", "INT-060");
        assertThat(r.overHitRate(SET.size() - SET.withExpected(Intent.EXECUTE).size()))
                .as("过度命中率是可测的，不是靠感觉").isGreaterThan(0.0);
    }

    @Test
    @DisplayName("失败详情逐条列出漏判，而不是只给一个比例")
    void describeListsEveryMiss() {
        ExecuteRecallGate.Result r = ExecuteRecallGate.check(SET,
                text -> hits(ORIGINAL_NINE_VERBS, text));

        String d = r.describe();
        assertThat(d).contains("EXECUTE 召回率 0.400").contains("漏判 15 条");
        // "召回率 0.40" 让人不知道从哪下手；逐条列出才是能行动的信息
        assertThat(d).contains("INT-011").contains("把 payment-api 的副本数调到 8");
        assertThat(d).contains("过度命中").contains("INT-055");
    }

    // ------------------------------------------------------------------ 标注集装载

    @Test
    @DisplayName("装载仓库里那份标注集，并守住它的结构不变量")
    void loadsTheShippedSet() {
        assertThat(SET.id()).isEqualTo("intent-v1");
        assertThat(SET.size()).isEqualTo(60);

        // 七个意图必须都有样本。少了哪一类，那一类的错误就永远测不到，
        // 而"某一类被悄悄删光"是标注集最容易发生的退化。
        for (Intent i : Intent.values()) {
            assertThat(SET.withExpected(i)).as("意图 %s 没有标注样本", i).isNotEmpty();
        }
    }

    @Test
    @DisplayName("★ 漏判专项组必须存在且有分量——它是整个集子的价值所在")
    void theParaphraseGroupCarriesTheWeight() {
        // 显式动词那一组是照着正则写的，它对正则没有任何检验力。
        // 真正能暴露漏判的只有这一组，所以下界要卡住：
        // 它缩水就等于门槛变松，而这种变松不会有任何报错。
        assertThat(SET.byGroup(IntentCase.GROUP_EXECUTE_PARAPHRASE).size())
                .isGreaterThanOrEqualTo(10);
    }

    @Test
    @DisplayName("★ 过度命中探针组必须存在，否则「什么都命中」也能拿满分")
    void theProbeGroupPreventsATrivialWin() {
        assertThat(SET.byGroup(IntentCase.GROUP_FALSE_POSITIVE_PROBE)).isNotEmpty();
        assertThat(SET.byGroup(IntentCase.GROUP_FALSE_POSITIVE_PROBE))
                .as("探针必须都不是 EXECUTE，否则它探不出过度命中")
                .noneMatch(c -> c.expect() == Intent.EXECUTE);
    }

    @Test
    @DisplayName("★ cases() 与 groups() 保持 YAML 里的顺序（Map.copyOf 会打乱）")
    void preservesYamlOrder() {
        // 这个断言看起来多余，但它钉的是一个真实回归：
        // byId 原来用 Map.copyOf，迭代顺序是哈希顺序，于是 cases() 的 javadoc
        // 承诺的「按文件里的顺序」是假的，漏判报告里的排列也变成哈希副产品，
        // 而任何按顺序写的断言会随机红。PromptRegistry 上踩过同一个坑两次。
        assertThat(SET.cases()).extracting(IntentCase::id)
                .containsSubsequence("INT-001", "INT-030", "INT-045", "INT-060")
                .hasSize(60);
        assertThat(SET.cases().get(0).id()).isEqualTo("INT-001");
        assertThat(SET.cases().get(59).id()).isEqualTo("INT-060");
        // groups() 的 javadoc 承诺「按首次出现顺序」
        assertThat(SET.groups().get(0)).isEqualTo("execute-explicit");
        assertThat(SET.groups().get(8)).isEqualTo("false-positive-probe");
    }

    @Test
    @DisplayName("id 重复 → 装载失败")
    void duplicateIdsAreRejected() {
        assertThatThrownBy(() -> IntentGoldenSet.load("golden-set/intent/duplicate-id.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("id 重复");
    }

    @Test
    @DisplayName("expect 落在闭集外 → 装载失败，并列出合法值")
    void unknownExpectLabelsAreRejected() {
        assertThatThrownBy(() -> IntentGoldenSet.load("golden-set/intent/bad-expect.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RESTART_EVERYTHING")
                .hasMessageContaining("OUT_OF_SCOPE");
    }

    @Test
    @DisplayName("cases 为空 → 装载失败（空标注集会让门槛的分母变成 0）")
    void emptyCaseListIsRejected() {
        assertThatThrownBy(() -> IntentGoldenSet.load("golden-set/intent/no-cases.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cases 为空");
    }

    @Test
    @DisplayName("资源不存在 → 报错并给出路径")
    void missingResourceIsRejected() {
        assertThatThrownBy(() -> IntentGoldenSet.load("golden-set/intent/nope.yaml"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("golden-set/intent/nope.yaml");
    }

    @Test
    @DisplayName("用例缺字段 → 装载失败")
    void casesMissingFieldsAreRejected() {
        assertThatThrownBy(() -> new IntentCase("X-1", "g", Intent.EXECUTE, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("缺少 text");
    }
}
