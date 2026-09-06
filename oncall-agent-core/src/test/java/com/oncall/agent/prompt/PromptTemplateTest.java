package com.oncall.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * prompt 模板的解析与渲染。
 *
 * <p>这里大部分用例守的不是"功能对不对"，而是<b>坏 prompt 会不会被挡住</b>。
 * 一个坏 prompt 的典型表现不是报错，而是"看起来像模像样地发给了模型"——
 * 少一个变量、多一个没替换掉的占位符、值变成字面量 {@code null}，
 * 全都不会抛异常，只会让回答悄悄变差。
 * 所以这些用例断言的是<b>必须抛</b>。
 */
@DisplayName("PromptTemplate：front-matter 解析、变量白名单、渲染")
class PromptTemplateTest {

    private static final PromptId ID = new PromptId("demo", "v1");

    // ------------------------------------------------------------------ 解析

    @Test
    @DisplayName("解析出 front-matter 里的变量声明与正文")
    void parsesHeaderAndBody() {
        PromptTemplate t = PromptTemplate.parse(ID, """
                ---
                variables: conversation, question
                ---
                历史：{{conversation}}
                问题：{{question}}
                """);

        assertThat(t.declaredVariables()).containsExactly("conversation", "question");
        assertThat(t.body()).contains("历史：{{conversation}}");
        assertThat(t.placeholders()).containsExactly("conversation", "question");
    }

    @Test
    @DisplayName("变量声明为空是合法的（纯静态 prompt），但 front-matter 本身不能省")
    void emptyVariableListIsAllowedButTheHeaderIsNot() {
        PromptTemplate t = PromptTemplate.parse(ID, """
                ---
                variables:
                ---
                一段没有任何变量的 prompt。
                """);
        assertThat(t.declaredVariables()).isEmpty();

        assertThatThrownBy(() -> PromptTemplate.parse(ID, "直接就是正文，没有 front-matter"))
                .isInstanceOf(PromptException.class)
                .extracting(e -> ((PromptException) e).kind())
                .isEqualTo(PromptException.Kind.MALFORMED_HEADER);
    }

    @Test
    @DisplayName("front-matter 没有闭合 → 报错")
    void unclosedHeaderIsRejected() {
        assertThatThrownBy(() -> PromptTemplate.parse(ID, "---\nvariables: a\n正文"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("没有闭合");
    }

    @Test
    @DisplayName("front-matter 里出现无法识别的键 → 报错，不是忽略")
    void unknownHeaderKeyIsRejected() {
        // 把 variables 少写一个 s 却静默生效，等于白名单形同虚设
        assertThatThrownBy(() -> PromptTemplate.parse(ID, """
                ---
                variable: a
                ---
                {{a}}
                """))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("无法识别的键")
                .hasMessageContaining("variable");
    }

    @Test
    @DisplayName("front-matter 有一行不是「键: 值」→ 报错")
    void malformedHeaderLineIsRejected() {
        assertThatThrownBy(() -> PromptTemplate.parse(ID, "---\n这一行没有冒号\n---\n正文"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("不是 \"键: 值\" 形式");
    }

    @Test
    @DisplayName("声明的变量名不合法 → 报错")
    void illegalVariableNameIsRejected() {
        assertThatThrownBy(() -> PromptTemplate.parse(ID, "---\nvariables: 1abc\n---\n正文"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("变量名不合法");
    }

    @Test
    @DisplayName("正文为空 → 报错")
    void emptyBodyIsRejected() {
        assertThatThrownBy(() -> PromptTemplate.parse(ID, "---\nvariables:\n---\n"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("正文为空");
    }

    // ------------------------------------------------------------------ 白名单

    @Test
    @DisplayName("正文用了未声明的占位符 → 报错（否则字面量 {{x}} 会一路发给模型）")
    void undeclaredPlaceholderIsRejected() {
        assertThatThrownBy(() -> PromptTemplate.parse(ID, """
                ---
                variables: a
                ---
                {{a}} 和 {{b}}
                """))
                .isInstanceOf(PromptException.class)
                .extracting(e -> ((PromptException) e).kind())
                .isEqualTo(PromptException.Kind.UNDECLARED_PLACEHOLDER);
    }

    @Test
    @DisplayName("声明了却没用到 → 报错（调用方会继续传一个不进 prompt 的变量）")
    void staleDeclarationIsRejected() {
        assertThatThrownBy(() -> PromptTemplate.parse(ID, """
                ---
                variables: a, unused
                ---
                只用了 {{a}}
                """))
                .isInstanceOf(PromptException.class)
                .extracting(e -> ((PromptException) e).kind())
                .isEqualTo(PromptException.Kind.STALE_DECLARATION);
    }

    @Test
    @DisplayName("JSON 示例里的单花括号不会被当成占位符——这就是选 {{}} 而不是 {} 的原因")
    void singleBracesInJsonExamplesAreNotPlaceholders() {
        PromptTemplate t = PromptTemplate.parse(ID, """
                ---
                variables: name
                ---
                你好 {{name}}，请按这个格式输出：
                {"intent": "CHITCHAT", "items": [{"text": "x"}]}
                """);

        assertThat(t.placeholders()).containsExactly("name");
        // 正文里的 JSON 原样保留，没有被替换逻辑碰过
        assertThat(t.render(Map.of("name", "张三"))).contains("{\"intent\": \"CHITCHAT\"");
    }

    // ------------------------------------------------------------------ 渲染

    @Test
    @DisplayName("同名占位符出现多次时全部替换")
    void rendersEveryOccurrence() {
        PromptTemplate t = PromptTemplate.of(ID, "{{x}} 和 {{x}} 还有 {{y}}", "x", "y");
        assertThat(t.render(Map.of("x", "A", "y", "B"))).isEqualTo("A 和 A 还有 B");
    }

    @Test
    @DisplayName("少传变量 → 报错")
    void missingVariableIsRejected() {
        PromptTemplate t = PromptTemplate.of(ID, "{{a}} {{b}}", "a", "b");
        assertThatThrownBy(() -> t.render(Map.of("a", "1")))
                .isInstanceOf(PromptException.class)
                .extracting(e -> ((PromptException) e).kind())
                .isEqualTo(PromptException.Kind.MISSING_VARIABLE);
    }

    @Test
    @DisplayName("多传变量 → 报错（多传通常意味着变量名打错，而打错的名字不会报错，只会悄悄消失）")
    void unexpectedVariableIsRejected() {
        PromptTemplate t = PromptTemplate.of(ID, "{{a}}", "a");
        assertThatThrownBy(() -> t.render(Map.of("a", "1", "aa", "2")))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("多传了未声明的变量");
    }

    @Test
    @DisplayName("变量值为 null → 报错，而不是变成字面量 \"null\"")
    void nullValueIsRejected() {
        PromptTemplate t = PromptTemplate.of(ID, "{{a}}", "a");
        Map<String, Object> vars = new HashMap<>();
        vars.put("a", null);

        assertThatThrownBy(() -> t.render(vars))
                .isInstanceOf(PromptException.class)
                .extracting(e -> ((PromptException) e).kind())
                .isEqualTo(PromptException.Kind.NULL_VARIABLE);
    }

    @Test
    @DisplayName("变量值里的 $ 与反斜杠原样插入（appendReplacement 的替换串有特殊含义）")
    void dollarAndBackslashInValuesAreInsertedLiterally() {
        PromptTemplate t = PromptTemplate.of(ID, "值：{{x}}", "x");
        String tricky = "价格 $100 与 $0，路径 C:\\tmp\\new";

        assertThat(t.render(Map.of("x", tricky))).isEqualTo("值：" + tricky);
    }

    @Test
    @DisplayName("变量值里的占位符不会被二次展开——这是安全边界")
    void placeholdersInsideAValueAreNotExpanded() {
        PromptTemplate t = PromptTemplate.of(ID, "用户说：{{x}}", "x");

        // 变量值往往来自用户输入或检索结果。允许二次展开，
        // 就等于让外部文本能引用任意占位符。
        assertThat(t.render(Map.of("x", "{{secret}} 和 {{x}}")))
                .isEqualTo("用户说：{{secret}} 和 {{x}}");
    }

    @Test
    @DisplayName("声明集合是不可变的：拿到它改不动模板")
    void declaredVariablesAreImmutable() {
        PromptTemplate t = PromptTemplate.of(ID, "{{a}}", "a");
        Set<String> declared = t.declaredVariables();

        assertThatThrownBy(() -> declared.add("b"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("PromptId 校验名字与版本的形状")
    void promptIdValidatesItsShape() {
        assertThatThrownBy(() -> new PromptId("Demo", "v1"))
                .isInstanceOf(PromptException.class).hasMessageContaining("prompt 名必须匹配");
        assertThatThrownBy(() -> new PromptId("demo", "1"))
                .isInstanceOf(PromptException.class).hasMessageContaining("以 v 开头");
        assertThat(new PromptId("intent-classify", "v3-canary").fileName())
                .isEqualTo("intent-classify.v3-canary.md");
    }
}
