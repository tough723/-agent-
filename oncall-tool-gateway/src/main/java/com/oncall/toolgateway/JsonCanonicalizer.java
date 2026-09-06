package com.oncall.toolgateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * JSON 参数的规范化 —— 幂等键的唯一输入。
 *
 * <p><b>为什么必须有</b>：幂等键是
 * {@code runId|step|toolName|canonical(args)}。原先 {@code GuardedToolCallback.canonical()}
 * 的 javadoc 写着「JSON key 排序 + 去空白」，而实现只有
 *
 * <pre>{@code return json.replaceAll("\\s+", "");}</pre>
 *
 * <b>根本没有排序</b>。于是 {@code {"a":1,"b":2}} 与 {@code {"b":2,"a":1}}
 * 会算出两个不同的幂等键——语义完全相同的两次请求被判成两次不同的操作，
 * <b>幂等静默失效</b>，而幂等失效的后果正是二次扩容、二次重启。
 *
 * <p>这与 {@code args_masked} 是同一类问题：<b>名字/javadoc 是一个断言，
 * 而实现没有兑现它，读代码的人会照着断言去推理</b>。
 * 区别在于这次的后果不是泄露，是重复执行高危操作。
 *
 * <p><b>为什么不自己写 JSON 解析器</b>：解析器的 bug 会直接变成幂等键的 bug，
 * 而这类 bug 的表现是「偶发的重复执行」，几乎不可能在测试里复现。
 * 所以用 Jackson（与 {@code oncall-agent-core} 一致，版本由 BOM 托管）。
 *
 * <h2>规范化做了什么</h2>
 * <ol>
 *   <li>对象键递归排序（按 UTF-16 码元序，确定性即可）</li>
 *   <li>数组顺序<b>原样保留</b>——数组顺序是有语义的，排序会改变含义</li>
 *   <li>去掉所有非结构性空白</li>
 *   <li>数字统一成 {@link BigDecimal#stripTrailingZeros()} 的形式：
 *       {@code 8} / {@code 8.0} / {@code 8.00} 规范化成同一个值。
 *       这一条不是洁癖——模型重试时完全可能把 {@code 8} 写成 {@code 8.0}，
 *       而那是同一个扩容请求。</li>
 * </ol>
 *
 * <p><b>刻意不声称兼容 RFC 8785 (JCS)</b>：JCS 要求键按 Unicode 码点排序，
 * 本类按 Java {@code String} 的自然序（UTF-16 码元）。对 BMP 内的键两者一致，
 * 对增补平面字符（emoji 等）会不同。幂等键只要求<b>确定性</b>，
 * 不要求跨语言互操作，所以不为此付复杂度——但也不假装自己是 JCS。
 */
public final class JsonCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonCanonicalizer() {
    }

    /**
     * 规范化一段 JSON 文本。
     *
     * @param json 原始参数文本
     * @return 规范化形式；{@code null} 与全空白都返回空串
     * @throws IllegalArgumentException 输入不是合法 JSON
     */
    public static String canonicalize(String json) {
        if (json == null || json.isBlank()) {
            return "";
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            // 抛出去而不是退回"去空白"：调用方需要知道规范化没成功。
            // 静默退回会让幂等键在无人察觉的情况下退化成字面量比较，
            // 而那正是本类要修的 bug。退回的决定权留给调用方（见 GuardedToolCallback）。
            throw new IllegalArgumentException(
                    "参数不是合法 JSON，无法规范化：" + e.getOriginalMessage(), e);
        }
        if (root == null || root.isMissingNode()) {
            return "";
        }
        try {
            return MAPPER.writeValueAsString(sortDeep(root));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("规范化后的结构无法序列化", e);
        }
    }

    /**
     * 两段参数是否语义等价。
     *
     * <p>供测试与自检使用：幂等的正确性判据是「语义相同 ⇒ 键相同」，
     * 而这句话只有在这个方法上才能被直接断言。
     */
    public static boolean semanticallyEqual(String a, String b) {
        try {
            return canonicalize(a).equals(canonicalize(b));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 递归重建：对象按键排序、数组保序、数字归一。 */
    private static JsonNode sortDeep(JsonNode node) {
        if (node.isObject()) {
            ObjectNode out = MAPPER.createObjectNode();
            List<String> names = new ArrayList<>();
            for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
                names.add(it.next());
            }
            names.sort(Comparator.naturalOrder());
            for (String n : names) {
                out.set(n, sortDeep(node.get(n)));
            }
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = MAPPER.createArrayNode();
            for (JsonNode e : node) {
                out.add(sortDeep(e));
            }
            return out;
        }
        if (node.isNumber()) {
            return normalizeNumber(node);
        }
        return node;
    }

    /**
     * 数字归一。
     *
     * <p>用 {@code BigDecimal} 的十进制字符串而不是 {@code double}：
     * 走 double 会把 {@code 0.1} 变成 {@code 0.1000000000000000055511151231257827}，
     * 那会让同一个参数在不同 JVM 上算出不同的幂等键。
     */
    private static JsonNode normalizeNumber(JsonNode node) {
        BigDecimal value;
        try {
            value = new BigDecimal(node.asText());
        } catch (NumberFormatException e) {
            return node;    // 解析不了的数字（NaN 之类）原样保留，不猜
        }
        BigDecimal stripped = value.stripTrailingZeros();
        // stripTrailingZeros 会把 100 变成 1E+2，toPlainString 再拉回 100。
        // 少了这一步，同一个整数会因为写法不同而得到两个键。
        return MAPPER.getNodeFactory().numberNode(stripped.toPlainString());
    }
}
