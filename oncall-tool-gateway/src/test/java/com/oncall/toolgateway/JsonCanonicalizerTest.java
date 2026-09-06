package com.oncall.toolgateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JSON 规范化的验收测试。
 *
 * <p><b>这一组测试守的是幂等的正确性</b>。幂等键是
 * {@code runId|step|toolName|canonical(args)}，所以
 * 「语义相同 ⇒ 键相同」不成立时，幂等就静默失效——
 * 而幂等失效的后果是二次扩容、二次重启。
 *
 * <p><b>反向同样要守</b>：语义不同的参数绝不能算出同一个键，
 * 否则两个不同的操作会被幂等吞掉一个，表现为「Agent 说做了但没做」。
 * 所以两侧都有断言。
 */
class JsonCanonicalizerTest {

    // ---------------------------------------------------------- 归一

    @Test
    @DisplayName("★ 键顺序不同 → 规范化形式相同（旧实现在这里失效，导致二次执行）")
    void keyOrderIsNormalized() {
        assertThat(JsonCanonicalizer.canonicalize("{\"a\":1,\"b\":2}"))
                .isEqualTo("{\"a\":1,\"b\":2}");
        assertThat(JsonCanonicalizer.canonicalize("{\"b\":2,\"a\":1}"))
                .as("键顺序不能影响幂等键")
                .isEqualTo("{\"a\":1,\"b\":2}");
        assertThat(JsonCanonicalizer.semanticallyEqual(
                "{\"a\":1,\"b\":2}", "{\"b\":2,\"a\":1}")).isTrue();
    }

    @Test
    @DisplayName("嵌套对象递归排序")
    void nestedObjectsAreSortedRecursively() {
        assertThat(JsonCanonicalizer.canonicalize("{\"o\":{\"b\":1,\"a\":2}}"))
                .isEqualTo("{\"o\":{\"a\":2,\"b\":1}}");
        assertThat(JsonCanonicalizer.canonicalize(
                "{\"scale\":{\"max\":10,\"min\":1},\"replicas\":8}"))
                .isEqualTo("{\"replicas\":8,\"scale\":{\"max\":10,\"min\":1}}");
    }

    @Test
    @DisplayName("★ 数组顺序原样保留——数组顺序是有语义的，排序会改变含义")
    void arrayOrderIsPreserved() {
        assertThat(JsonCanonicalizer.canonicalize("[3,1,2]")).isEqualTo("[3,1,2]");
        assertThat(JsonCanonicalizer.canonicalize("{\"l\":[3,1,2]}")).isEqualTo("{\"l\":[3,1,2]}");
        assertThat(JsonCanonicalizer.semanticallyEqual(
                "{\"t\":[\"a\",\"b\"]}", "{\"t\":[\"b\",\"a\"]}"))
                .as("顺序不同的数组是两个不同的操作")
                .isFalse();
    }

    @Test
    @DisplayName("非结构性空白全部去掉")
    void insignificantWhitespaceIsRemoved() {
        assertThat(JsonCanonicalizer.canonicalize("{\"a\" : 1 , \"b\" : [ 1 , 2 ]}"))
                .isEqualTo("{\"a\":1,\"b\":[1,2]}");
        assertThat(JsonCanonicalizer.canonicalize("  {\"a\"  :  1}  ")).isEqualTo("{\"a\":1}");
    }

    @Test
    @DisplayName("★ 数字归一：8 / 8.0 / 8.00 是同一个值")
    void numbersAreNormalized() {
        assertThat(JsonCanonicalizer.canonicalize("8")).isEqualTo("8");
        assertThat(JsonCanonicalizer.canonicalize("8.0")).isEqualTo("8");
        assertThat(JsonCanonicalizer.canonicalize("8.00")).isEqualTo("8");
        assertThat(JsonCanonicalizer.canonicalize("0.10")).isEqualTo("0.1");
        assertThat(JsonCanonicalizer.semanticallyEqual("{\"r\":8}", "{\"r\":8.0}"))
                .as("模型重试时把 8 写成 8.0，那是同一个扩容请求")
                .isTrue();
    }

    @Test
    @DisplayName("整数不会变成科学计数法——那会让键变得不可读且难排查")
    void integersDoNotGoScientific() {
        assertThat(JsonCanonicalizer.canonicalize("1000000")).isEqualTo("1000000");
        assertThat(JsonCanonicalizer.canonicalize("{\"n\":1000000}")).isEqualTo("{\"n\":1000000}");
    }

    @Test
    @DisplayName("★ 字符串值不做数字归一：\"8.0\" 是文本，不是数字")
    void stringValuesAreNotNumberNormalized() {
        assertThat(JsonCanonicalizer.canonicalize("{\"a\":\"8.0\"}")).isEqualTo("{\"a\":\"8.0\"}");
        assertThat(JsonCanonicalizer.semanticallyEqual("{\"a\":\"8.0\"}", "{\"a\":\"8\"}"))
                .as("把文本当数字归一会让两个不同的参数撞上同一个幂等键")
                .isFalse();
    }

    @Test
    @DisplayName("null 与布尔原样保留")
    void nullAndBooleansArePreserved() {
        assertThat(JsonCanonicalizer.canonicalize("{\"a\":null,\"b\":false}"))
                .isEqualTo("{\"a\":null,\"b\":false}");
    }

    @Test
    @DisplayName("中文原样保留，不转成 \\uXXXX")
    void unicodeIsPreserved() {
        assertThat(JsonCanonicalizer.canonicalize("{\"msg\":\"你好\"}")).isEqualTo("{\"msg\":\"你好\"}");
    }

    @Test
    @DisplayName("规范化是幂等的：再规范化一次结果不变")
    void canonicalizationIsIdempotent() {
        String[] samples = {
            "{\"b\":2,\"a\":{\"d\":4,\"c\":3}}", "[3,1,2]", "{\"a\":\"8.0\"}",
            "{\"n\":8.00}", "{\"msg\":\"你好\"}", "{\"a\":null}",
        };
        for (String s : samples) {
            String once = JsonCanonicalizer.canonicalize(s);
            assertThat(JsonCanonicalizer.canonicalize(once))
                    .as("二次规范化不应再改变结果：%s", s)
                    .isEqualTo(once);
        }
    }

    // ---------------------------------------------------------- 边界

    @Test
    @DisplayName("null 与全空白返回空串")
    void nullAndBlankBecomeEmpty() {
        assertThat(JsonCanonicalizer.canonicalize(null)).isEmpty();
        assertThat(JsonCanonicalizer.canonicalize("   ")).isEmpty();
    }

    @Test
    @DisplayName("★ 非法 JSON 当场拒绝，而不是退回「去空白」——退回的决定权属于调用方")
    void malformedJsonIsRejected() {
        for (String bad : new String[]{"{", "{\"a\":}", "not json", "{\"a\":1"}) {
            assertThatThrownBy(() -> JsonCanonicalizer.canonicalize(bad))
                    .as("非法输入必须抛出来：%s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("不是合法 JSON");
        }
        // 抛出去而不是静默退回：静默退回会让幂等键在无人察觉的情况下
        // 退化成字面量比较，而那正是本类要修的 bug。
        assertThat(JsonCanonicalizer.semanticallyEqual("{", "{\"a\":1}"))
                .as("任一侧非法都判为不等价，不猜")
                .isFalse();
    }

    @Test
    @DisplayName("语义不同的参数绝不能规范化成同一个形式")
    void differentValuesStayDifferent() {
        assertThat(JsonCanonicalizer.semanticallyEqual("{\"a\":1}", "{\"a\":2}")).isFalse();
        assertThat(JsonCanonicalizer.semanticallyEqual("{\"a\":1}", "{\"b\":1}")).isFalse();
        assertThat(JsonCanonicalizer.semanticallyEqual("{\"a\":1}", "{\"a\":1,\"b\":2}")).isFalse();
        assertThat(JsonCanonicalizer.semanticallyEqual("{\"a\":1}", "[1]")).isFalse();
    }
}
