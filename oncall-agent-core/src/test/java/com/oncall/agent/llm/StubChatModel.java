package com.oncall.agent.llm;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 返回预设响应的假模型。
 *
 * <p><b>它的作用不是"模拟 AI"，而是把编排逻辑从非确定性里摘出来。</b>
 * 真模型进 CI 就是随机红：红了没人分得清是逻辑坏了还是模型今天不高兴。
 * 有了它，「Planner 返回缺字段的 JSON 是否被拒」「引用校验失败是否走降级」
 * 这类分支才可能稳定触发。
 *
 * <p><b>{@link #receivedPrompts()} 是这个类最重要的部分</b>：
 * 上下文装配是否正确，只能通过"模型收到了什么"来验证。
 * 没有它，"RAG 到底有没有接上"这类问题只能靠肉眼看日志——
 * 而这正是原方案里 RAG 没生效却长期没被发现的原因。
 *
 * <p>放在 {@code src/test/java} 而不是 main：它是测试替身，不该进生产 jar。
 * 与 {@code oncall-tool-gateway} 的 {@code StubToolCallback} 同一个先例。
 *
 * <p><b>可见性是 public，因为它被同模块内另一个包用</b>：
 * {@code com.oncall.agent.query} 的测试需要它来驱动 {@code IntentClassifier}。
 * 宁可放宽一个测试替身的可见性，也不要在第二个包里复制一份——
 * 两份替身会各自漂移，而漂移的替身会让下游断言因为错误的原因通过。
 * 若将来<b>跨模块</b>也要用，再提升为独立 test-jar 并记下理由。
 */
public final class StubChatModel implements ChatModel {

    private final Deque<ChatResponse> scripted = new ArrayDeque<>();
    private final List<String> receivedPrompts = new ArrayList<>();
    /** 脚本原本有几条。报错信息要用它，不能用 receivedPrompts 的长度。 */
    private final int scriptSize;
    /** 见 {@link #getDefaultOptions()}：刻意缓存，让调用方能做同一性断言。 */
    private final ChatOptions options = ChatOptions.builder().build();

    private int failuresRemaining;
    private RuntimeException failure;

    private StubChatModel(List<ChatResponse> responses) {
        scripted.addAll(responses);
        this.scriptSize = responses.size();
    }

    /** 依次返回这些文本，每个文本包成一个单 {@link Generation} 的响应。 */
    public static StubChatModel returning(String... texts) {
        List<ChatResponse> rs = new ArrayList<>();
        for (String t : texts) {
            rs.add(new ChatResponse(List.of(new Generation(new AssistantMessage(t)))));
        }
        return new StubChatModel(rs);
    }

    /**
     * 依次返回这些响应。需要自定义 metadata（如 token 用量）时用这个。
     *
     * <p><b>刻意不叫 {@code returning}</b>：与 {@code returning(String...)} 同名时，
     * 零参调用 {@code StubChatModel.returning()} 会同时匹配两个重载，
     * 而 {@code String} 与 {@code ChatResponse} 互不为子类型，
     * 编译器判不出更具体的那个——直接报"引用不明确"。
     * 这种错只在"恰好有人写了零参调用"时才暴露，改名比记住这条规则可靠。
     */
    public static StubChatModel respondingWith(ChatResponse... responses) {
        return new StubChatModel(List.of(responses));
    }

    /** 前 {@code n} 次调用抛异常，之后才按脚本返回。用来测重试与 failover。 */
    public StubChatModel failFirst(int n, RuntimeException e) {
        if (n < 0) {
            throw new IllegalArgumentException("n 不能为负：" + n);
        }
        this.failuresRemaining = n;
        this.failure = e;
        return this;
    }

    /**
     * 模型实际收到的 prompt 文本，按调用顺序。
     *
     * <p>取的是 {@link Prompt#getContents()}——它把 system / user / assistant
     * 各条消息的文本拼在一起，也就是模型真正"看到"的东西。
     * 刻意不只取最后一条 user 消息：那样测不出"system prompt 到底有没有带上"。
     */
    public List<String> receivedPrompts() {
        return List.copyOf(receivedPrompts);
    }

    public String lastPrompt() {
        return receivedPrompts.isEmpty() ? null : receivedPrompts.get(receivedPrompts.size() - 1);
    }

    /** 被调用的次数，<b>包括失败的那几次</b>——失败的调用同样说明模型被触达过。 */
    public int callCount() {
        return receivedPrompts.size();
    }

    /**
     * 固定返回同一个 {@code ChatOptions} 实例。
     *
     * <p>接口的默认实现每次都 {@code ChatOptions.builder().build()} 一个新对象，
     * 那样就没法用 {@code isSameAs} 断言"装饰器确实委托给了主模型"——
     * 而"委托给了主模型"和"委托给了备用模型"是这个测试唯一要区分的事。
     */
    @Override
    public ChatOptions getDefaultOptions() {
        return options;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        receivedPrompts.add(prompt.getContents());
        if (failuresRemaining > 0) {
            failuresRemaining--;
            throw failure;
        }
        ChatResponse r = scripted.poll();
        if (r == null) {
            // 刻意抛而不是返回 null：脚本用尽是测试写错了，
            // 返回 null 会让被测代码在别处炸掉，排查方向直接跑偏。
            //
            // 这里用 scriptSize 而不是 receivedPrompts.size() 报"准备了几条"：
            // 后者把失败的调用也算进去了，报出来的数字会随 failFirst 变化，
            // 而一条会变的报错信息等于没有信息。
            throw new IllegalStateException("StubChatModel 的脚本已用尽：脚本准备了 "
                    + scriptSize + " 条响应，第 " + receivedPrompts.size()
                    + " 次调用没有对应响应。要么补脚本，"
                    + "要么说明被测代码比预期多调了一次模型。");
        }
        return r;
    }
}
