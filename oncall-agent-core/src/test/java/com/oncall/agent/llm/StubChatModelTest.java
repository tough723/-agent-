package com.oncall.agent.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 测测试替身本身。
 *
 * <p><b>这不是仪式。</b>{@code StubChatModel} 是后面所有 L2 测试的地基，
 * 如果 {@link StubChatModel#receivedPrompts()} 返回的东西不对，
 * 那么每一个"上下文装配正确"的断言都会<b>以错误的理由通过</b>——
 * 而那种失败比没有测试更难发现，因为它会一直绿下去。
 *
 * <p>尤其是"到底记了整段 prompt 还是只记了 user 消息"这一条：
 * 只记 user 消息的话，「system prompt 有没有带上」这类问题永远测不出来，
 * 而测试会照样全绿。
 */
@DisplayName("StubChatModel：测试替身自身的行为")
class StubChatModelTest {

    @Test
    @DisplayName("按脚本顺序返回预设响应")
    void returnsScriptedResponsesInOrder() {
        StubChatModel stub = StubChatModel.returning("第一条", "第二条");

        assertThat(text(stub.call(new Prompt("a")))).isEqualTo("第一条");
        assertThat(text(stub.call(new Prompt("b")))).isEqualTo("第二条");
    }

    @Test
    @DisplayName("按调用顺序记下每一个 prompt")
    void recordsEveryPromptInOrder() {
        StubChatModel stub = StubChatModel.returning("x", "y", "z");

        stub.call(new Prompt("第一次"));
        stub.call(new Prompt("第二次"));

        assertThat(stub.receivedPrompts()).containsExactly("第一次", "第二次");
        assertThat(stub.lastPrompt()).isEqualTo("第二次");
    }

    @Test
    @DisplayName("记的是整段 prompt（含 system），不是只有最后一条 user 消息")
    void capturesTheWholePromptNotJustTheUserMessage() {
        StubChatModel stub = StubChatModel.returning("ok");

        stub.call(new Prompt(List.<Message>of(
                new SystemMessage("你是 OnCall 排障助手，不得编造工单号。"),
                new UserMessage("order-service 一直在重启"))));

        // 这条断言是整个 L2 层的地基：system prompt 丢了要能测出来
        assertThat(stub.lastPrompt())
                .contains("不得编造工单号")
                .contains("order-service 一直在重启");
    }

    @Test
    @DisplayName("脚本用尽时抛出，且报错里带上脚本长度——返回 null 会让排查方向跑偏")
    void scriptExhaustionThrowsAndReportsTheScriptSize() {
        StubChatModel stub = StubChatModel.returning("只有一条");
        stub.call(new Prompt("a"));

        assertThatThrownBy(() -> stub.call(new Prompt("b")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("脚本准备了 1 条响应")
                .hasMessageContaining("第 2 次调用");
    }

    @Test
    @DisplayName("failFirst：前 n 次抛异常，之后才按脚本返回")
    void failFirstThrowsThenFallsBackToTheScript() {
        StubChatModel stub = StubChatModel.returning("终于成功了")
                .failFirst(2, new IllegalStateException("模拟 429"));

        assertThatThrownBy(() -> stub.call(new Prompt("a")))
                .isInstanceOf(IllegalStateException.class).hasMessage("模拟 429");
        assertThatThrownBy(() -> stub.call(new Prompt("b")))
                .isInstanceOf(IllegalStateException.class).hasMessage("模拟 429");
        assertThat(text(stub.call(new Prompt("c")))).isEqualTo("终于成功了");
    }

    @Test
    @DisplayName("callCount 把失败的调用也算进去——失败的调用同样说明模型被触达过")
    void callCountIncludesFailedCalls() {
        StubChatModel stub = StubChatModel.returning("ok")
                .failFirst(2, new IllegalStateException("boom"));

        assertThatThrownBy(() -> stub.call(new Prompt("a"))).isInstanceOf(IllegalStateException.class);
        assertThat(stub.callCount()).isEqualTo(1);

        assertThatThrownBy(() -> stub.call(new Prompt("b"))).isInstanceOf(IllegalStateException.class);
        assertThat(stub.callCount()).isEqualTo(2);

        stub.call(new Prompt("c"));
        assertThat(stub.callCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("failFirst 的负数次数被拒绝")
    void failFirstRejectsNegativeCount() {
        StubChatModel stub = StubChatModel.returning("ok");
        assertThatThrownBy(() -> stub.failFirst(-1, new IllegalStateException("x")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("receivedPrompts 返回不可变副本：调用方改不动替身的内部状态")
    void receivedPromptsIsAnImmutableCopy() {
        StubChatModel stub = StubChatModel.returning("ok");
        stub.call(new Prompt("a"));
        List<String> snapshot = stub.receivedPrompts();

        assertThatThrownBy(() -> snapshot.add("伪造的一次调用"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(stub.receivedPrompts()).hasSize(1);
    }

    @Test
    @DisplayName("respondingWith 原样返回给定的响应实例，metadata 不被改写")
    void respondingWithReturnsTheExactResponseInstance() {
        ChatResponse scripted = ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("带元数据的响应"))))
                .metadata("model", "deepseek-v4-flash")
                .build();

        StubChatModel stub = StubChatModel.respondingWith(scripted);

        ChatResponse got = stub.call(new Prompt("q"));
        // 用同一性而不是内容比较：一旦替身偷偷重建了响应，
        // 后面所有依赖 metadata（token 用量、finish reason）的 L2 测试都会失真。
        assertThat(got).isSameAs(scripted);
        assertThat(text(got)).isEqualTo("带元数据的响应");
    }

    private static String text(ChatResponse r) {
        return r.getResult().getOutput().getText();
    }
}
