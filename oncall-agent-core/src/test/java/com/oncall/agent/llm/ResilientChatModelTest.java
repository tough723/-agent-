package com.oncall.agent.llm;

import com.oncall.agent.llm.ResilientChatModel.ModelEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * failover 与重试的行为测试——《测试与交付保障体系》里五个 L2 场景中的第一个。
 *
 * <p><b>为什么这一层能用 {@code StubChatModel} 稳定测</b>：
 * 真模型的 429 什么时候来、来几次，完全不可控；
 * 而"主模型挂了要不要换、换之前要不要退避重试"是纯逻辑，
 * 一旦能用假件触发，就该有确定性的断言。
 *
 * <p><b>刻意不断言真实耗时</b>：{@link ResilientChatModel.Sleeper} 是注入的，
 * 测试里换成"只记录不睡眠"。这既让测试跑得快，
 * 也避免断言 CI 机器上的时序——本项目已经踩过一次
 * "断言并发测试的时序副产物"的坑。
 */
@DisplayName("ResilientChatModel：模型 failover 与重试")
class ResilientChatModelTest {

    private final List<Long> sleeps = new ArrayList<>();
    private final List<String> failures = new ArrayList<>();

    private final ResilientChatModel.Sleeper recordingSleeper = sleeps::add;
    private final ResilientChatModel.FailureListener recordingListener =
            (model, attempt, error, willRetry) ->
                    failures.add(model + "#" + attempt + ":" + willRetry);

    // ------------------------------------------------------------------ 正常路径

    @Test
    @DisplayName("主模型成功 ⇒ 链上其余模型一次都不碰")
    void primarySuccessSkipsTheRestOfTheChain() {
        StubChatModel primary = StubChatModel.returning("主模型答的");
        StubChatModel backup = StubChatModel.returning("备用模型答的");

        ChatResponse r = resilient(RetryPolicy.failoverOnly(),
                model("primary", primary), model("backup", backup)).call(new Prompt("q"));

        assertThat(text(r)).isEqualTo("主模型答的");
        assertThat(primary.callCount()).isEqualTo(1);
        assertThat(backup.callCount()).isZero();
    }

    @Test
    @DisplayName("备用模型收到的是同一个 prompt——failover 不能把上下文丢掉")
    void theBackupReceivesTheSamePrompt() {
        StubChatModel primary = alwaysFailing("主模型挂了");
        StubChatModel backup = StubChatModel.returning("备用模型答的");

        resilient(RetryPolicy.failoverOnly(),
                model("primary", primary), model("backup", backup)).call(new Prompt("order-service 一直重启"));

        assertThat(backup.lastPrompt()).isEqualTo("order-service 一直重启");
    }

    // ------------------------------------------------------------------ failover

    @Test
    @DisplayName("不可重试的失败 ⇒ 立刻换模型，一次都不多试")
    void nonRetryableFailureFailsOverImmediately() {
        StubChatModel primary = alwaysFailing("401 密钥错了");
        StubChatModel backup = StubChatModel.returning("备用模型答的");

        // maxAttempts=3 但 retryable=false：多试两次只是白等两倍的退避时间，
        // 而这段时间恰好花在"主模型已经不正常"的时候。
        RetryPolicy policy = new RetryPolicy(3, 100L, 2.0d, 8_000L, t -> false);

        ChatResponse r = resilient(policy,
                model("primary", primary), model("backup", backup)).call(new Prompt("q"));

        assertThat(text(r)).isEqualTo("备用模型答的");
        assertThat(primary.callCount()).as("不可重试就不该在同一个模型上多试").isEqualTo(1);
        assertThat(backup.callCount()).isEqualTo(1);
        assertThat(sleeps).as("没重试就不该退避").isEmpty();
    }

    @Test
    @DisplayName("可重试的失败 ⇒ 在同一个模型上退避重试，重试成功就不惊动备用模型")
    void retryableFailureIsRetriedOnTheSameModel() {
        StubChatModel primary = StubChatModel.returning("第三次才成功")
                .failFirst(2, new IllegalStateException("429 限流"));
        StubChatModel backup = StubChatModel.returning("备用模型答的");

        RetryPolicy policy = new RetryPolicy(3, 100L, 2.0d, 8_000L, t -> true);

        ChatResponse r = resilient(policy,
                model("primary", primary), model("backup", backup)).call(new Prompt("q"));

        assertThat(text(r)).isEqualTo("第三次才成功");
        assertThat(primary.callCount()).isEqualTo(3);
        assertThat(backup.callCount()).as("重试成功了就不该 failover").isZero();
    }

    @Test
    @DisplayName("重试次数用尽 ⇒ 才换下一个模型")
    void failoverHappensOnlyAfterRetriesAreExhausted() {
        StubChatModel primary = alwaysFailing("一直 429");
        StubChatModel backup = StubChatModel.returning("备用模型答的");

        RetryPolicy policy = new RetryPolicy(4, 100L, 2.0d, 8_000L, t -> true);

        resilient(policy, model("primary", primary), model("backup", backup)).call(new Prompt("q"));

        assertThat(primary.callCount()).isEqualTo(4);
        assertThat(backup.callCount()).isEqualTo(1);
        assertThat(sleeps).containsExactly(100L, 200L, 400L);
    }

    @Test
    @DisplayName("链上全部失败 ⇒ 抛 ModelExhaustedException，带链长度与最后一次的异常")
    void allModelsFailingThrowsModelExhaustedException() {
        StubChatModel primary = alwaysFailing("主模型的错误");
        StubChatModel backup = alwaysFailing("备用模型的错误");

        ResilientChatModel r = resilient(RetryPolicy.failoverOnly(),
                model("primary", primary), model("backup", backup));

        assertThatThrownBy(() -> r.call(new Prompt("q")))
                .isInstanceOf(ModelExhaustedException.class)
                .hasMessageContaining("2 个模型全部失败")
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("备用模型的错误");

        assertThat(primary.callCount()).isEqualTo(1);
        assertThat(backup.callCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("failoverOnly 就是每个模型只试一次")
    void failoverOnlyMakesExactlyOneAttemptPerModel() {
        assertThat(RetryPolicy.failoverOnly().maxAttemptsPerModel()).isEqualTo(1);

        StubChatModel a = alwaysFailing("a");
        StubChatModel b = alwaysFailing("b");
        StubChatModel c = alwaysFailing("c");

        assertThatThrownBy(() -> resilient(RetryPolicy.failoverOnly(),
                model("a", a), model("b", b), model("c", c)).call(new Prompt("q")))
                .isInstanceOf(ModelExhaustedException.class);

        assertThat(a.callCount()).isEqualTo(1);
        assertThat(b.callCount()).isEqualTo(1);
        assertThat(c.callCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 失败可见性

    @Test
    @DisplayName("每一次失败都上报，包括最终成功之前的那些")
    void failureListenerSeesEveryAttempt() {
        StubChatModel primary = StubChatModel.returning("第三次才成功")
                .failFirst(2, new IllegalStateException("429"));

        RetryPolicy policy = new RetryPolicy(3, 100L, 2.0d, 8_000L, t -> true);
        resilient(policy, model("primary", primary)).call(new Prompt("q"));

        // 界面上一切正常，但主模型其实已经限流了两次——
        // 不报告这两次，就没人知道备用模型的配额正在被主模型的故障消耗。
        assertThat(failures).containsExactly("primary#1:true", "primary#2:true");
    }

    @Test
    @DisplayName("换模型那一次的 willRetry 是 false")
    void theAttemptThatTriggersFailoverReportsWillRetryFalse() {
        StubChatModel primary = alwaysFailing("挂了");
        StubChatModel backup = StubChatModel.returning("ok");

        RetryPolicy policy = new RetryPolicy(2, 100L, 2.0d, 8_000L, t -> true);
        resilient(policy, model("primary", primary), model("backup", backup)).call(new Prompt("q"));

        assertThat(failures).containsExactly("primary#1:true", "primary#2:false");
    }

    // ------------------------------------------------------------------ 退避

    @Test
    @DisplayName("退避按倍数增长并被上限截住")
    void backoffGrowsExponentiallyAndIsCapped() {
        RetryPolicy policy = new RetryPolicy(5, 100L, 2.0d, 350L, t -> true);

        assertThat(policy.backoffBeforeRetry(1)).isEqualTo(100L);
        assertThat(policy.backoffBeforeRetry(2)).isEqualTo(200L);
        assertThat(policy.backoffBeforeRetry(3)).as("400 超过上限 350，应被截住").isEqualTo(350L);
        assertThat(policy.backoffBeforeRetry(4)).isEqualTo(350L);
    }

    @Test
    @DisplayName("退避等待由注入的 Sleeper 承担，测试里不真的睡")
    void sleeperReceivesTheBackoffSequence() {
        StubChatModel primary = alwaysFailing("挂了");
        StubChatModel backup = StubChatModel.returning("ok");

        RetryPolicy policy = new RetryPolicy(3, 250L, 3.0d, 8_000L, t -> true);
        resilient(policy, model("primary", primary), model("backup", backup)).call(new Prompt("q"));

        assertThat(sleeps).containsExactly(250L, 750L);
    }

    // ------------------------------------------------------------------ 装配校验

    @Test
    @DisplayName("空链在构造时就被拒绝：那是装配错误，不该等第一次告警进来才发现")
    void emptyChainIsRejectedAtConstruction() {
        assertThatThrownBy(() -> resilient(RetryPolicy.failoverOnly()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }

    @Test
    @DisplayName("链上的 null 项被拒绝")
    void nullEntryInChainIsRejected() {
        assertThatThrownBy(() -> new ResilientChatModel(
                java.util.Arrays.asList(model("primary", StubChatModel.returning("ok")), null),
                RetryPolicy.failoverOnly(), recordingSleeper, recordingListener))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("模型标识不能为空——链上两个模型往往是同一个类的两个实例，标识是唯一的区分手段")
    void blankModelNameIsRejected() {
        assertThatThrownBy(() -> model("  ", StubChatModel.returning("ok")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RetryPolicy 拒绝无意义的取值")
    void retryPolicyRejectsImpossibleValues() {
        assertThatThrownBy(() -> new RetryPolicy(0, 100L, 2.0d, 1_000L, t -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("至少为 1");
        assertThatThrownBy(() -> new RetryPolicy(3, 100L, 0.5d, 1_000L, t -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("加压");
        assertThatThrownBy(() -> new RetryPolicy(3, 1_000L, 2.0d, 500L, t -> true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能小于");
        assertThatThrownBy(() -> new RetryPolicy(3, -1L, 2.0d, 1_000L, t -> true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetryPolicy(3, 100L, 2.0d, 1_000L, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("chainSize 暴露链长度，供健康检查与告警文案使用")
    void chainSizeIsExposed() {
        ResilientChatModel r = resilient(RetryPolicy.failoverOnly(),
                model("a", StubChatModel.returning("1")),
                model("b", StubChatModel.returning("2")));
        assertThat(r.chainSize()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ 委托

    @Test
    @DisplayName("getDefaultOptions 委托给主模型，而不是编一个默认值")
    void getDefaultOptionsDelegatesToThePrimaryModel() {
        StubChatModel primary = StubChatModel.returning("ok");
        StubChatModel backup = StubChatModel.returning("ok");

        ResilientChatModel r = resilient(RetryPolicy.failoverOnly(),
                model("primary", primary), model("backup", backup));

        assertThat(r.getDefaultOptions()).isSameAs(primary.getDefaultOptions());
        assertThat(r.getDefaultOptions()).isNotSameAs(backup.getDefaultOptions());
    }

    @Test
    @DisplayName("Sleeper 被中断时恢复中断标志位——吞掉它会让上层的取消逻辑失效")
    void interruptedSleepRestoresTheInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> ResilientChatModel.Sleeper.threadSleep().sleep(50))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("被中断");
            // Thread.interrupted() 读取并清除标志位，顺带把测试线程恢复干净
            assertThat(Thread.interrupted()).as("中断标志必须被恢复").isTrue();
        } finally {
            // 万一断言提前失败，也要把标志清掉，免得污染后续测试
            Thread.interrupted();
        }
    }

    // ------------------------------------------------------------------ helpers

    private ResilientChatModel resilient(RetryPolicy policy, ModelEntry... chain) {
        return new ResilientChatModel(List.of(chain), policy, recordingSleeper, recordingListener);
    }

    private static ModelEntry model(String name, org.springframework.ai.chat.model.ChatModel m) {
        return ModelEntry.of(name, m);
    }

    private static StubChatModel alwaysFailing(String message) {
        return StubChatModel.returning().failFirst(Integer.MAX_VALUE, new IllegalStateException(message));
    }

    private static String text(ChatResponse r) {
        return r.getResult().getOutput().getText();
    }
}
