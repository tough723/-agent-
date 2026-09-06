package com.oncall.agent.query;

import com.oncall.agent.llm.StubChatModel;
import com.oncall.agent.prompt.ActiveVersionSource;
import com.oncall.agent.prompt.PromptRegistry;
import com.oncall.config.ConfigService;
import com.oncall.config.InMemoryConfigAuditLog;
import com.oncall.config.InMemoryConfigStore;
import com.oncall.config.OnCallConfigKeys;
import com.oncall.config.OnCallConfigRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 查询理解的两层分工与四道护栏。
 *
 * <p>这里最该被守住的一条是<b>「规则层命中后，模型无权下调 EXECUTE」</b>。
 * 它是 {@code EXECUTE} 召回率 = 1.0 这个硬门槛的实现方式——
 * 用正则而不是模型来保门槛，因为正则是可枚举、可回归、可证明的，
 * 而模型的召回率只能统计。<b>安全门槛不能建在统计量上。</b>
 *
 * <p>被测对象是<b>真的</b>：真的 {@code PromptRegistry}（从 classpath 读到
 * 生产那份 {@code intent-classify.v1.md}）、真的 {@code ConfigService}
 * （真的配置声明表 + 内存存储）。只有模型是替身。
 */
@DisplayName("IntentClassifier：规则层定安全，LLM 只做路由")
class IntentClassifierTest {

    private static final String OK_JSON = """
            {"intent":"QUERY_HISTORY","standaloneQuery":"payment-api 历史上是否 CPU 超 90%",\
            "rewritten":true,"resolvedEntities":[{"text":"这个服务","resolvedTo":"payment-api","source":"turn-2"}],\
            "confidence":0.86}""";

    // ------------------------------------------------------------------ ① 规则层

    @Test
    @DisplayName("正则命中操作类动词 → EXECUTE，且标记为规则层判定")
    void regexHitPinsExecute() {
        IntentClassifier c = classifier(StubChatModel.returning(OK_JSON));

        QueryUnderstanding r = c.classify("帮我重启 payment-api 的实例");

        assertThat(r.intent()).isEqualTo(Intent.EXECUTE);
        assertThat(r.intentFromRule()).isTrue();
        assertThat(r.intent().isWriteIntent()).isTrue();
    }

    @Test
    @DisplayName("★ 规则层命中后，模型说什么都不能把 EXECUTE 下调掉")
    void modelCannotDowngradeARuleHit() {
        // 这是 EXECUTE 召回率 = 1.0 的实现方式：靠结构，不靠模型的准确率
        IntentClassifier c = classifier(StubChatModel.returning(
                "{\"intent\":\"EXPLAIN_ALERT\",\"standaloneQuery\":\"x\",\"rewritten\":false,"
                        + "\"resolvedEntities\":[],\"confidence\":0.99}"));

        QueryUnderstanding r = c.classify("把 order-service 下线");

        assertThat(r.intent()).isEqualTo(Intent.EXECUTE);
        assertThat(r.intentFromRule()).isTrue();
        assertThat(r.llmIntent()).isEqualTo(Intent.EXPLAIN_ALERT);
        assertThat(r.intentDisagreesWithRule()).isTrue();
    }

    @Test
    @DisplayName("规则层命中时仍然调用模型——为的是留下分歧数据，那是调正则的唯一依据")
    void ruleHitStillCallsTheModel() {
        StubChatModel stub = StubChatModel.returning(OK_JSON);
        IntentClassifier c = classifier(stub);

        c.classify("清理一下 node-3 上的临时文件");

        assertThat(stub.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("只读请求不会被误判成 EXECUTE")
    void readOnlyRequestsAreNotFlagged() {
        IntentClassifier c = classifier(StubChatModel.returning(OK_JSON));

        assertThat(IntentClassifier.EXECUTE_INTENT.matcher("payment-api 昨天为什么告警").find())
                .as("纯查询句不该命中操作类正则").isFalse();
        assertThat(c.classify("payment-api 昨天为什么告警").intentFromRule()).isFalse();
    }

    @Test
    @DisplayName("★ 模型挂了 + 规则层命中 → 请求照样进审批闸门")
    void modelFailureDoesNotLoseAnExecuteRequest() {
        StubChatModel stub = StubChatModel.returning("用不到");
        stub.failFirst(1, new IllegalStateException("429"));
        IntentClassifier c = classifier(stub);

        QueryUnderstanding r = c.classify("回滚 payment-api 到上一个版本");

        assertThat(r.intent()).isEqualTo(Intent.EXECUTE);
        assertThat(r.intentFromRule()).isTrue();
        assertThat(r.modelOutputSuspect()).isTrue();
        assertThat(r.degradeReason()).contains("模型调用失败");
    }

    @Test
    @DisplayName("★ 模型挂了 + 规则层没命中 → 拒绝，而不是猜一个意图")
    void modelFailureWithoutRuleHitRefuses() {
        StubChatModel stub = StubChatModel.returning("用不到");
        stub.failFirst(1, new IllegalStateException("timeout"));
        IntentClassifier c = classifier(stub);

        QueryUnderstanding r = c.classify("payment-api 最近怎么样");

        assertThat(r.intent()).isEqualTo(Intent.OUT_OF_SCOPE);
        assertThat(r.intentFromRule()).isFalse();
        assertThat(r.modelOutputSuspect()).isTrue();
        assertThat(r.standaloneQuery()).as("降级时用原句检索").isEqualTo("payment-api 最近怎么样");
        assertThat(r.rewritten()).isFalse();
    }

    @Test
    @DisplayName("空问题直接拒绝构造调用")
    void blankQuestionIsRejected() {
        IntentClassifier c = classifier(StubChatModel.returning(OK_JSON));
        assertThatThrownBy(() -> c.classify("  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ prompt 与归因

    @Test
    @DisplayName("结果带着实际用到的 prompt 版本——这是 llm_call_log 归因的前提")
    void promptVersionIsCarriedForAttribution() {
        IntentClassifier c = classifier(StubChatModel.returning(OK_JSON));

        assertThat(c.classify("这个服务上次也这样吗？").promptVersion()).isEqualTo("v1");
    }

    @Test
    @DisplayName("渲染后的 prompt 真的到了模型手里，问题与历史都被替换进去了")
    void theRenderedPromptReachesTheModel() {
        StubChatModel stub = StubChatModel.returning(OK_JSON);
        IntentClassifier c = classifier(stub);

        c.classify("那这个服务上次也这样吗？", "turn-1 用户：payment-api CPU 高");

        String sent = stub.lastPrompt();
        assertThat(sent).contains("那这个服务上次也这样吗？");
        assertThat(sent).contains("turn-1 用户：payment-api CPU 高");
        assertThat(sent).as("占位符不能留字面量").doesNotContain("{{question}}");
    }

    // ------------------------------------------------------------------ ③ 护栏

    @Test
    @DisplayName("护栏 2：置信度低于阈值 → 不改写，用原句")
    void lowConfidenceSuppressesTheRewrite() {
        IntentClassifier c = classifier(StubChatModel.returning(
                "{\"intent\":\"QUERY_HISTORY\",\"standaloneQuery\":\"改歪了的查询\","
                        + "\"rewritten\":true,\"resolvedEntities\":[],\"confidence\":0.30}"));

        QueryUnderstanding r = c.classify("那它上次也这样吗？");

        assertThat(r.rewritten()).isFalse();
        assertThat(r.standaloneQuery()).isEqualTo("那它上次也这样吗？");
        assertThat(r.rewriteSuppressedReason()).contains("置信度").contains("0.3");
    }

    @Test
    @DisplayName("阈值是 RUNTIME_HOT：改了立刻生效，不需要重启")
    void theThresholdIsReadPerCallNotAtConstruction() {
        // 两次 classify 就要脚本两条响应：脚本用尽时 StubChatModel 会抛，
        // 于是走降级分支、rewritten 恒为 false——那样这条用例会因为
        // 「替身没脚本」而失败，看起来却像「热更新没生效」。
        String json = "{\"intent\":\"QUERY_HISTORY\",\"standaloneQuery\":\"自包含的查询\","
                + "\"rewritten\":true,\"resolvedEntities\":[],\"confidence\":0.30}";
        InMemoryConfigStore store = new InMemoryConfigStore();
        IntentClassifier c = classifier(StubChatModel.returning(json, json), store);

        assertThat(c.classify("那它上次也这样吗？").rewritten())
                .as("默认阈值 0.7，置信度 0.30 应当被挡下").isFalse();

        // 同一个 classifier 实例，只改配置
        store.put(OnCallConfigKeys.QUERY_REWRITE_MIN_CONFIDENCE, "0.2");
        QueryUnderstanding second = c.classify("那它上次也这样吗？");

        // 先确认模型这一侧是正常的，否则下面的断言没有意义
        assertThat(second.degradeReason()).as("第二次调用不该走降级").isNull();
        assertThat(second.rewritten()).as("阈值降到 0.2 之后应当采用改写").isTrue();
    }

    @Test
    @DisplayName("query.rewrite-enabled 关掉 → 一律不改写（这是「改歪」的一键兜底）")
    void rewriteCanBeDisabledEntirely() {
        InMemoryConfigStore store = new InMemoryConfigStore();
        store.put(OnCallConfigKeys.QUERY_REWRITE_ENABLED, "false");
        IntentClassifier c = classifier(StubChatModel.returning(OK_JSON), store);

        QueryUnderstanding r = c.classify("这个服务上次也这样吗？");

        assertThat(r.rewritten()).isFalse();
        assertThat(r.rewriteSuppressedReason()).contains("rewrite-enabled");
    }

    @Test
    @DisplayName("★ 护栏 3：模型声称消解的指代不在原句里 → 整个改写作废")
    void aFabricatedEntityVoidsTheWholeRewrite() {
        // 原句里没有「那个集群」，模型却说自己把它解析成了 k8s-prod。
        // 有一条是编的就说明这次输出整体不可信：少丢一点换不回什么，
        // 改歪的代价是答非所问且用户看不出来。
        IntentClassifier c = classifier(StubChatModel.returning(
                "{\"intent\":\"QUERY_HISTORY\",\"standaloneQuery\":\"k8s-prod 的历史故障\","
                        + "\"rewritten\":true,\"resolvedEntities\":["
                        + "{\"text\":\"那个集群\",\"resolvedTo\":\"k8s-prod\",\"source\":\"turn-1\"}],"
                        + "\"confidence\":0.95}"));

        QueryUnderstanding r = c.classify("这个服务上次也这样吗？");

        assertThat(r.rewritten()).isFalse();
        assertThat(r.standaloneQuery()).isEqualTo("这个服务上次也这样吗？");
        assertThat(r.resolvedEntities()).as("编造的消解结果不该回显给用户").isEmpty();
        assertThat(r.rewriteSuppressedReason()).contains("疑似编造");
    }

    @Test
    @DisplayName("指代确实出现在原句里 → 消解结果保留，供 UI 回显")
    void aGroundedEntityIsKeptForEcho() {
        IntentClassifier c = classifier(StubChatModel.returning(OK_JSON));

        QueryUnderstanding r = c.classify("这个服务上次也这样吗？");

        assertThat(r.rewritten()).isTrue();
        assertThat(r.resolvedEntities()).hasSize(1);
        assertThat(r.resolvedEntities().get(0).resolvedTo()).isEqualTo("payment-api");
    }

    @Test
    @DisplayName("模型声称改写了但 standaloneQuery 为空 → 当作没改写")
    void rewrittenWithoutAQueryIsIgnored() {
        IntentClassifier c = classifier(StubChatModel.returning(
                "{\"intent\":\"QUERY_HISTORY\",\"standaloneQuery\":\"\","
                        + "\"rewritten\":true,\"resolvedEntities\":[],\"confidence\":0.9}"));

        QueryUnderstanding r = c.classify("那它呢？");

        assertThat(r.rewritten()).isFalse();
        assertThat(r.standaloneQuery()).isEqualTo("那它呢？");
    }

    @Test
    @DisplayName("模型自报未改写 → 用原句")
    void modelDecliningToRewriteIsRespected() {
        IntentClassifier c = classifier(StubChatModel.returning(
                "{\"intent\":\"CHITCHAT\",\"standaloneQuery\":\"随便\","
                        + "\"rewritten\":false,\"resolvedEntities\":[],\"confidence\":0.9}"));

        QueryUnderstanding r = c.classify("你好");

        assertThat(r.rewritten()).isFalse();
        assertThat(r.standaloneQuery()).isEqualTo("你好");
        assertThat(r.rewriteSuppressedReason()).contains("自报未改写");
    }

    @Test
    @DisplayName("缺字段的实体条目直接丢掉，不塞 null 进去")
    void incompleteEntitiesAreDropped() {
        IntentClassifier c = classifier(StubChatModel.returning(
                "{\"intent\":\"QUERY_HISTORY\",\"standaloneQuery\":\"payment-api 的历史\","
                        + "\"rewritten\":true,\"resolvedEntities\":["
                        + "{\"text\":\"这个服务\"},"
                        + "{\"text\":\"这个服务\",\"resolvedTo\":\"payment-api\",\"source\":\"turn-1\"}],"
                        + "\"confidence\":0.9}"));

        QueryUnderstanding r = c.classify("这个服务上次也这样吗？");

        // 一条不完整的消解结果回显给用户，比没有回显更容易误导
        assertThat(r.resolvedEntities()).hasSize(1);
    }

    // ------------------------------------------------------------------ 输出解析

    @Test
    @DisplayName("模型把 JSON 包在代码围栏里也能解析——否则一次可用的回答会被白白拒掉")
    void jsonInsideACodeFenceIsParsed() {
        IntentClassifier c = classifier(StubChatModel.returning(
                "```json\n" + OK_JSON + "\n```"));

        assertThat(c.classify("这个服务上次也这样吗？").intent()).isEqualTo(Intent.QUERY_HISTORY);
    }

    @Test
    @DisplayName("模型在 JSON 前面加了一句客套话也能解析")
    void jsonAfterLeadingProseIsParsed() {
        IntentClassifier c = classifier(StubChatModel.returning("好的，以下是结果：\n" + OK_JSON));

        assertThat(c.classify("这个服务上次也这样吗？").intent()).isEqualTo(Intent.QUERY_HISTORY);
    }

    @Test
    @DisplayName("输出根本不是 JSON → 降级，不是抛给调用方")
    void unparseableOutputDegrades() {
        IntentClassifier c = classifier(StubChatModel.returning("我不知道你在说什么"));

        QueryUnderstanding r = c.classify("这个服务上次也这样吗？");

        assertThat(r.intent()).isEqualTo(Intent.OUT_OF_SCOPE);
        assertThat(r.modelOutputSuspect()).isTrue();
    }

    @Test
    @DisplayName("模型返回空响应 → 降级")
    void emptyResponseDegrades() {
        IntentClassifier c = classifier(StubChatModel.returning("   "));

        assertThat(c.classify("这个服务上次也这样吗？").modelOutputSuspect()).isTrue();
    }

    @Test
    @DisplayName("意图标签落在闭集之外 → 拒绝，并记下原始标签")
    void unknownIntentLabelRefusesAndRecordsTheLabel() {
        IntentClassifier c = classifier(StubChatModel.returning(
                "{\"intent\":\"REBOOT_EVERYTHING\",\"standaloneQuery\":\"x\","
                        + "\"rewritten\":false,\"resolvedEntities\":[],\"confidence\":0.9}"));

        QueryUnderstanding r = c.classify("这个服务上次也这样吗？");

        assertThat(r.intent()).isEqualTo(Intent.OUT_OF_SCOPE);
        assertThat(r.llmIntent()).isNull();
        assertThat(r.degradeReason()).contains("REBOOT_EVERYTHING");
    }

    @Test
    @DisplayName("置信度越界视为契约违背，而不是「很有把握」")
    void outOfRangeConfidenceDegrades() {
        IntentClassifier c = classifier(StubChatModel.returning(
                "{\"intent\":\"QUERY_HISTORY\",\"standaloneQuery\":\"x\","
                        + "\"rewritten\":true,\"resolvedEntities\":[],\"confidence\":1.5}"));

        QueryUnderstanding r = c.classify("这个服务上次也这样吗？");

        assertThat(r.modelOutputSuspect()).isTrue();
        assertThat(r.degradeReason()).contains("confidence");
    }

    @Test
    @DisplayName("extractJson：剥围栏、取首个 { 到末个 }")
    void extractJsonHandlesTheCommonWrappings() {
        assertThat(IntentClassifier.extractJson("```json\n{\"a\":1}\n```")).isEqualTo("{\"a\":1}");
        assertThat(IntentClassifier.extractJson("结果：{\"a\":1} 以上")).isEqualTo("{\"a\":1}");
        assertThat(IntentClassifier.extractJson("{\"a\":1}")).isEqualTo("{\"a\":1}");
        assertThat(IntentClassifier.extractJson("完全没有 JSON"))
                .as("没有花括号时原样返回，让上层去报「无法解析」")
                .isEqualTo("完全没有 JSON");
    }

    // ------------------------------------------------------------------ 枚举

    @Test
    @DisplayName("Intent.parse 容忍大小写与空白，闭集外返回 empty 而不是猜")
    void intentParseIsLenientAboutFormButStrictAboutMembership() {
        assertThat(Intent.parse(" query_history ")).contains(Intent.QUERY_HISTORY);
        assertThat(Intent.parse("out_of_scope")).contains(Intent.OUT_OF_SCOPE);
        assertThat(Intent.parse("RESTART")).isEmpty();
        assertThat(Intent.parse(null)).isEmpty();
    }

    @Test
    @DisplayName("只有 EXECUTE 与 CONFIGURE 是写意图；DIAGNOSE 本身只读")
    void writeIntentsAreExactlyExecuteAndConfigure() {
        assertThat(Intent.EXECUTE.isWriteIntent()).isTrue();
        assertThat(Intent.CONFIGURE.isWriteIntent()).isTrue();
        // DIAGNOSE 产出的是计划，计划里的写操作在各自那一步再受闸门约束
        assertThat(Intent.DIAGNOSE.isWriteIntent()).isFalse();
        assertThat(Intent.EXPLAIN_ALERT.isWriteIntent()).isFalse();
        assertThat(Intent.OUT_OF_SCOPE.isWriteIntent()).isFalse();
    }

    @Test
    @DisplayName("结果里的实体列表不可变")
    void resolvedEntitiesAreImmutable() {
        IntentClassifier c = classifier(StubChatModel.returning(OK_JSON));
        List<ResolvedEntity> entities = c.classify("这个服务上次也这样吗？").resolvedEntities();

        assertThatThrownBy(() -> entities.add(new ResolvedEntity("x", "y", null)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static IntentClassifier classifier(StubChatModel model) {
        return classifier(model, new InMemoryConfigStore());
    }

    private static IntentClassifier classifier(StubChatModel model, InMemoryConfigStore store) {
        PromptRegistry prompts = PromptRegistry.fromClasspath(
                List.of("intent-classify"),
                ActiveVersionSource.fixed(Map.of("intent-classify", "v1")));
        ConfigService config = new ConfigService(
                OnCallConfigRegistry.create(), store, new InMemoryConfigAuditLog());
        return new IntentClassifier(model, prompts, config);
    }
}
