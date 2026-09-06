package com.oncall.agent.llm;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;
import java.util.Objects;

/**
 * 按顺序尝试一条模型链的 {@link ChatModel} 装饰器：
 * 前一个不可用就换下一个，全部失败才抛 {@link ModelExhaustedException}。
 *
 * <p>这是 {@code oncall-agent} 的第一个生产类，选它先做的理由是：
 * 在《测试与交付保障体系》列的五个 L2 场景里，
 * 「模型抛 429 → 是否切 failover」是<b>唯一一个不需要任何新的领域决策</b>的。
 * 其余四个（Planner 的 JSON 校验、高危步骤拦截、引用校验、上下文超预算）
 * 都要先定下计划格式与引用契约，那些决定不该被一个可靠性组件顺手做掉。
 *
 * <p><b>三件事它刻意不做：</b>
 *
 * <p><b>① 不判断异常可不可重试。</b>见 {@link RetryPolicy} 的类注释：
 * 我们此刻不知道 429 会以什么类型出现，猜错的两个方向都代价不小。
 *
 * <p><b>② 不支持流式。</b>{@code stream(Prompt)} 沿用接口的默认实现
 * （抛 {@code UnsupportedOperationException}），这是有意的：
 * 流式响应一旦已经往用户屏幕上吐了半句话，再重试就会让用户看到重复内容，
 * 而"重试"这个动作本身对用户是不可见的。要重试就必须在吐出第一个 token
 * 之前决定，那是另一套设计（缓冲 + 阈值），不在这个类的职责里。
 *
 * <p><b>③ 不读配置。</b>{@code fallback.model-failover-chain} 已经存在，
 * 但把它读进来是应用模块的事。这一层保持零内部依赖，
 * 任何模块都能拿它去包装自己的 {@code ChatModel}。
 */
public final class ResilientChatModel implements ChatModel {

    private final List<ModelEntry> chain;
    private final RetryPolicy policy;
    private final Sleeper sleeper;
    private final FailureListener listener;
    private final Ticker ticker;
    private final CallObserver observer;

    /**
     * 四参构造：真实时钟 + 静默观测。
     *
     * <p>保留它是为了不破坏已有调用点；<b>但生产装配不应该用它</b>——
     * 静默观测意味着 {@code llm_call_log} 那四列永远填不出来。
     * 见 {@link CallObserver} 的类注释。
     */
    public ResilientChatModel(List<ModelEntry> chain, RetryPolicy policy,
                              Sleeper sleeper, FailureListener listener) {
        this(chain, policy, sleeper, listener,
                Ticker.systemNanoTime(), CallObserver.silent());
    }

    public ResilientChatModel(List<ModelEntry> chain, RetryPolicy policy,
                              Sleeper sleeper, FailureListener listener,
                              Ticker ticker, CallObserver observer) {
        Objects.requireNonNull(chain, "chain");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(sleeper, "sleeper");
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(ticker, "ticker");
        Objects.requireNonNull(observer, "observer");
        if (chain.isEmpty()) {
            // 空链意味着 call() 必然抛 ModelExhaustedException，
            // 而那是装配错误，不是运行期故障——应该在启动时就炸，
            // 而不是等到第一次告警进来才发现"AI 从来没通过"。
            throw new IllegalArgumentException("failover 链不能为空");
        }
        for (ModelEntry e : chain) {
            Objects.requireNonNull(e, "failover 链中不能有 null 项");
        }
        this.chain = List.copyOf(chain);
        this.policy = policy;
        this.sleeper = sleeper;
        this.listener = listener;
        this.ticker = ticker;
        this.observer = observer;
    }

    /** 便捷构造：真实睡眠 + 静默监听。测试里请用六参构造器注入假 Sleeper 与 Ticker。 */
    public ResilientChatModel(List<ModelEntry> chain, RetryPolicy policy) {
        this(chain, policy, Sleeper.threadSleep(), FailureListener.silent());
    }

    /**
     * 依次尝试链上的模型。
     *
     * <p>两种失败的区别对待很关键：
     * <ul>
     *   <li><b>可重试</b>（{@code retryable} 判定为真）⇒ 在同一个模型上退避后重试。
     *       典型是限流——换个模型并不能让配额回来。</li>
     *   <li><b>不可重试</b> ⇒ <b>立刻</b>换下一个模型，一次都不多试。
     *       典型是密钥错误——在同一个模型上重试三次只是白等三倍的退避时间，
     *       而这段时间恰好花在"主模型已经不正常"的时候。</li>
     * </ul>
     */
    @Override
    public ChatResponse call(Prompt prompt) {
        Objects.requireNonNull(prompt, "prompt");
        RuntimeException last = null;
        // 从进入 call() 就开始计时：latency_ms 必须包含重试与退避的全部时间。
        // 只测「最后一次成功尝试」的话，P95 会好看很多，
        // 而用户等的那 8 秒退避恰好被排除在外——那正是 DDL 注释点名的硬伤。
        long startedAtNanos = ticker.nanoTime();
        String abandoned = null;
        for (ModelEntry entry : chain) {
            for (int attempt = 1; attempt <= policy.maxAttemptsPerModel(); attempt++) {
                try {
                    ChatResponse response = entry.model().call(prompt);
                    observer.onSuccess(new CallOutcome(
                            entry.name(),
                            (ticker.nanoTime() - startedAtNanos) / 1_000_000L,
                            attempt,
                            abandoned));
                    return response;
                } catch (RuntimeException e) {
                    // 只接 RuntimeException：call(Prompt) 没有声明受检异常，
                    // 而 Error（OOM、栈溢出）必须原样往上抛，重试它们毫无意义。
                    last = e;
                    boolean willRetry = attempt < policy.maxAttemptsPerModel()
                            && policy.retryable().test(e);
                    listener.onAttemptFailed(entry.name(), attempt, e, willRetry);
                    if (!willRetry) {
                        break;
                    }
                    sleeper.sleep(policy.backoffBeforeRetry(attempt));
                }
            }
            // 整个模型都被放弃了，记下它——它就是下一个模型的 failoverFrom。
            // 放在内层循环之外：同一个模型上的多次重试不算 failover，
            // 那是 is_retry 的语义，两列在 DDL 里是分开的。
            abandoned = entry.name();
        }
        throw new ModelExhaustedException(chain.size(), last);
    }

    /**
     * 委托给链上第一个（主）模型。
     *
     * <p>不返回一个合成的默认值：调用方拿到 options 是为了知道"这次会用什么参数"，
     * 编一个假的比不给更糟。
     */
    @Override
    public ChatOptions getDefaultOptions() {
        return chain.get(0).model().getDefaultOptions();
    }

    /** 链上有几个模型。写进健康检查与告警文案。 */
    public int chainSize() {
        return chain.size();
    }

    /**
     * 链上的一项。
     *
     * @param name  模型标识，用于日志与 {@link FailureListener}。
     *              <b>不要用类名</b>——链上两个模型往往是同一个实现类的两个实例，
     *              类名分不出是主模型挂了还是备用模型挂了，
     *              而这正是排障时唯一想知道的事。
     * @param model 实际的模型
     */
    public record ModelEntry(String name, ChatModel model) {

        public ModelEntry {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("模型标识不能为空");
            }
            Objects.requireNonNull(model, "model");
        }

        public static ModelEntry of(String name, ChatModel model) {
            return new ModelEntry(name, model);
        }
    }

    /**
     * 退避等待。抽成接口是为了让测试<b>不真的睡</b>——
     * 一个要等 8 秒的重试测试既慢又会被 CI 的超时误伤。
     */
    @FunctionalInterface
    public interface Sleeper {

        void sleep(long millis);

        /** 生产用的真实睡眠。被中断时恢复中断标志位再抛出。 */
        static Sleeper threadSleep() {
            return millis -> {
                try {
                    Thread.sleep(millis);
                } catch (InterruptedException e) {
                    // 吞掉中断标志会让上层的取消逻辑失效，必须先恢复
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("failover 退避等待被中断", e);
                }
            };
        }
    }

    /**
     * 单调时钟。抽成接口的理由与 {@link Sleeper} 完全相同：
     * 让测试不必真的等，也让延迟可以被确定性断言。
     *
     * <p><b>用 {@code nanoTime} 而不是 {@code currentTimeMillis}</b>：
     * 墙上时钟会被 NTP 往回拨，一次回拨就能让 {@code latency_ms} 变成负数，
     * 而 {@link CallOutcome} 会把它拒掉——于是变成一次莫名其妙的异常。
     * 单调时钟不会往回走，这个失败模式从根上不存在。
     */
    @FunctionalInterface
    public interface Ticker {

        long nanoTime();

        /** 生产用的真实单调时钟。 */
        static Ticker systemNanoTime() {
            return System::nanoTime;
        }
    }

    /**
     * <b>成功</b>调用的观测口。与 {@link FailureListener} 对称——
     * 后者只报失败，前者报成功，两个合起来才够填满 {@code llm_call_log}。
     *
     * <p><b>为什么这个口必须存在：</b>
     * {@code llm_call_log} 的 {@code model} / {@code latency_ms} / {@code is_retry}
     * 三列是 {@code NOT NULL}，而它们<b>只有这一层知道</b>——
     * 调用方拿到的是一个 {@code ChatResponse}，上面既没有模型标识也没有耗时。
     * 没有这个口，写这张表的代码就只能编造这三列，
     * 而一张字段造假的计量表比没有计量更糟：它会给出看起来可信的错误成本。
     *
     * <p>本接口<b>刻意不写库</b>：凑齐一行还需要 {@code prompt_version} 与
     * {@code call_type}，那两列的知识在 {@code agent.prompt} 与编排层，
     * 而 F11 禁止本包依赖它们。所以这里只交事实，落库由上层做。
     *
     * @see CallOutcome
     */
    @FunctionalInterface
    public interface CallObserver {

        void onSuccess(CallOutcome outcome);

        /**
         * 什么都不做的观测器。
         *
         * <p>它是默认值而不是唯一选项——<b>用它就等于放弃 {@code llm_call_log} 的三列必填字段</b>。
         * 留它存在只是为了让「不关心计量」的调用方（例如单元测试）不必写一个空 lambda。
         */
        static CallObserver silent() {
            return outcome -> {
            };
        }
    }

    /**
     * 每次尝试失败的回调。
     *
     * <p><b>必须报告每一次失败，包括最终成功之前的那些。</b>
     * 理由和 MCP 纳管里"报告每一个被拒工具"是同一条：
     * failover 成功时界面上一切正常，
     * 而主模型其实已经挂了一整天——这种静默降级比直接报错危险得多，
     * 因为它把"备用模型的配额正在被主模型的故障消耗"这件事藏起来了。
     */
    @FunctionalInterface
    public interface FailureListener {

        /**
         * @param model     失败的模型标识
         * @param attempt   这是该模型上的第几次尝试，从 1 开始
         * @param error     异常
         * @param willRetry 是否还会在同一个模型上重试；
         *                  {@code false} 表示接下来会换模型（或整条链已耗尽）
         */
        void onAttemptFailed(String model, int attempt, Throwable error, boolean willRetry);

        static FailureListener silent() {
            return (model, attempt, error, willRetry) -> {
            };
        }
    }
}
