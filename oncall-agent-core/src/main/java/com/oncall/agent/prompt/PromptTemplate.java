package com.oncall.agent.prompt;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一个不可变的 prompt 版本：正文 + 它声明的变量白名单。
 *
 * <p><b>为什么变量要"声明"，而不是直接从正文里扫出来</b>
 * （《测试与交付保障体系》§2.2 第 2 条硬约束）：
 * 声明是<b>与调用方之间的契约</b>，它让两个方向的漂移都能被抓住——
 * <ul>
 *   <li><b>正文用了未声明的变量</b>：作者往正文里加了 {@code {{newVar}}} 却忘了声明。
 *       不挡的话，渲染结果里会留下字面量 <code>{{newVar}}</code> 一路发给模型。
 *       这不是报错，是一段"看起来像模像样"的坏 prompt——最难发现的那类故障。
 *       它同时也是注入入口：正文里出现什么占位符，就等于允许调用方注入什么。</li>
 *   <li><b>声明了却没用</b>：作者删掉了用法但没删声明。
 *       调用方会继续传一个根本不进 prompt 的变量，静默无效。</li>
 * </ul>
 *
 * <p><b>占位符语法是 {@code {{name}}} 而不是 {@code {name}}</b>：
 * 这些 prompt 里必然包含 JSON 示例（意图分类要输出 JSON，Planner 要输出计划），
 * 用单花括号就得把示例里每一个 <code>{</code> 都转义一遍，
 * 而漏转义一处的表现是"prompt 悄悄变了"，不是报错。
 * 这也是<b>刻意不复用 Spring AI {@code PromptTemplate}</b> 的原因——
 * 它基于 StringTemplate，占位符正是单花括号。
 */
public record PromptTemplate(PromptId id, String body, Set<String> declaredVariables) {

    /** {@code {{ name }}}，允许内侧留白。 */
    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    private static final String FENCE = "---";
    private static final String HEADER_VARIABLES = "variables";

    public PromptTemplate {
        Objects.requireNonNull(id, "id");
        if (body == null || body.isBlank()) {
            throw new PromptException(PromptException.Kind.MALFORMED_HEADER,
                    id.fileName() + " 的正文为空");
        }
        // 用 TreeSet 而不是 Set.copyOf：后者的迭代顺序不保证，
        // 而这份集合会进错误消息、也会被调用方拿去比对。顺序抖动看起来就像
        // 「声明自己变了」，在一个以声明为契约的地方那是最坏的误导。
        declaredVariables = declaredVariables == null
                ? java.util.Collections.unmodifiableSet(new TreeSet<String>())
                : java.util.Collections.unmodifiableSet(new TreeSet<>(declaredVariables));

        Set<String> used = placeholdersOf(body);
        Set<String> undeclared = new TreeSet<>(used);
        undeclared.removeAll(declaredVariables);
        if (!undeclared.isEmpty()) {
            throw new PromptException(PromptException.Kind.UNDECLARED_PLACEHOLDER,
                    id.fileName() + " 的正文里出现了未声明的占位符 " + undeclared
                            + "；已声明的是 " + describe(declaredVariables)
                            + "。未声明的占位符会被原样发给模型，所以必须挡在这里");
        }
        Set<String> stale = new TreeSet<>(declaredVariables);
        stale.removeAll(used);
        if (!stale.isEmpty()) {
            throw new PromptException(PromptException.Kind.STALE_DECLARATION,
                    id.fileName() + " 声明了 " + stale + " 但正文里从未使用；"
                            + "调用方会继续传一个不进 prompt 的变量，静默无效");
        }
    }

    /**
     * 从带 front-matter 的文件内容解析。
     *
     * <pre>
     * ---
     * variables: conversation, question
     * ---
     * 正文……
     * </pre>
     *
     * <p>front-matter 刻意用这种极简格式而不是 YAML：
     * 只有一个键，引 YAML 解析器是为了一个逗号分隔的列表引入一整个依赖，
     * 而且 YAML 的缩进/引号规则会让"改一行 prompt"多出几种失败方式。
     *
     * <p><b>无法识别的键直接报错</b>，不是忽略：
     * 把 {@code variable:} 少写一个 s 却静默生效，等于白名单形同虚设。
     */
    public static PromptTemplate parse(PromptId id, String content) {
        Objects.requireNonNull(id, "id");
        if (content == null) {
            throw new PromptException(PromptException.Kind.RESOURCE_ERROR,
                    id.fileName() + " 的内容为 null");
        }
        String text = content.replace("\r\n", "\n");
        String opening = FENCE + "\n";
        if (!text.startsWith(opening)) {
            throw new PromptException(PromptException.Kind.MALFORMED_HEADER,
                    id.fileName() + " 缺少 front-matter：文件必须以一行 \"" + FENCE + "\" 开头，"
                            + "用来声明变量白名单（没有变量也要写 " + HEADER_VARIABLES + ": 空）");
        }
        int close = text.indexOf("\n" + FENCE + "\n", opening.length() - 1);
        if (close < 0) {
            throw new PromptException(PromptException.Kind.MALFORMED_HEADER,
                    id.fileName() + " 的 front-matter 没有闭合的 \"" + FENCE + "\" 行");
        }
        String header = text.substring(opening.length(), close);
        String body = text.substring(close + FENCE.length() + 2);
        return new PromptTemplate(id, body, parseHeader(id, header));
    }

    private static Set<String> parseHeader(PromptId id, String header) {
        Set<String> declared = new LinkedHashSet<>();
        for (String rawLine : header.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw new PromptException(PromptException.Kind.MALFORMED_HEADER,
                        id.fileName() + " 的 front-matter 有一行不是 \"键: 值\" 形式：" + line);
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!HEADER_VARIABLES.equals(key)) {
                throw new PromptException(PromptException.Kind.MALFORMED_HEADER,
                        id.fileName() + " 的 front-matter 含有无法识别的键 \"" + key
                                + "\"，只支持 \"" + HEADER_VARIABLES + "\"。"
                                + "拼错的键静默忽略会让变量白名单形同虚设");
            }
            if (!value.isEmpty()) {
                for (String v : value.split(",")) {
                    String name = v.trim();
                    if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                        throw new PromptException(PromptException.Kind.MALFORMED_HEADER,
                                id.fileName() + " 声明的变量名不合法：" + name);
                    }
                    declared.add(name);
                }
            }
        }
        return declared;
    }

    /** 正文里实际出现的占位符名，按字母序。 */
    public Set<String> placeholders() {
        return placeholdersOf(body);
    }

    /**
     * 渲染。
     *
     * <p><b>{@code vars} 的键集合必须与声明的变量集合完全相等</b>，
     * 多一个少一个都报错。看起来严格，但两个方向都是真 bug：
     * 少传 ⇒ prompt 里缺内容；多传 ⇒ 变量名打错了，而打错的名字不会报错，
     * 只会让那个变量悄悄消失。
     *
     * <p><b>值为 {@code null} 也报错</b>：直接拼进去会变成字面量 {@code "null"}，
     * 模型会把它当成真实内容读——比抛异常糟得多。
     */
    public String render(Map<String, Object> vars) {
        Map<String, Object> given = vars == null ? Map.of() : vars;

        Set<String> missing = new TreeSet<>(declaredVariables);
        missing.removeAll(given.keySet());
        Set<String> unexpected = new TreeSet<>(given.keySet());
        unexpected.removeAll(declaredVariables);
        if (!missing.isEmpty() || !unexpected.isEmpty()) {
            // 两侧一起报，而不是先报缺的那个就抛：
            // 「少一个 name、多一个 nmae」是同一个拼写错误的两半，
            // 只看到一半的人不会想到是字母顺序打反了。
            throw new PromptException(
                    missing.isEmpty() ? PromptException.Kind.UNEXPECTED_VARIABLE
                            : PromptException.Kind.MISSING_VARIABLE,
                    id.fileName() + " 渲染时变量不匹配"
                            + (missing.isEmpty() ? "" : "；缺少 " + missing)
                            + (unexpected.isEmpty() ? "" : "；多传了未声明的 " + unexpected)
                            + "。声明的是 " + describe(declaredVariables)
                            + "。多传通常意味着变量名打错了，而打错的名字不会报错，只会悄悄消失");
        }
        for (Map.Entry<String, Object> e : given.entrySet()) {
            if (e.getValue() == null) {
                throw new PromptException(PromptException.Kind.NULL_VARIABLE,
                        id.fileName() + " 的变量 " + e.getKey() + " 为 null；"
                                + "直接拼进 prompt 会变成字面量 \"null\"，模型会当成真实内容读");
            }
        }

        Matcher m = PLACEHOLDER.matcher(body);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = String.valueOf(given.get(m.group(1)));
            // quoteReplacement 是必需的：变量值里的 $ 和 \ 在 appendReplacement 里
            // 有特殊含义，不转义会让替换结果与输入不符——而 prompt 里出现
            // 正则、路径、shell 片段都很常见。
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);
        return out.toString();
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 单趟扫描正文完成替换。
     *
     * <p><b>刻意不递归展开</b>：变量<b>值</b>里如果出现 {@code {{other}}}，
     * 不会被二次替换。这一条是安全边界——变量值往往来自用户输入或检索结果，
     * 允许二次展开就等于让外部文本能引用任意占位符。
     */
    private static Set<String> placeholdersOf(String text) {
        Matcher m = PLACEHOLDER.matcher(text);
        Set<String> names = new TreeSet<>();
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static String describe(Set<String> set) {
        return set.isEmpty() ? "（无）" : new TreeSet<>(set).toString();
    }

    /** 便捷构造：无变量的 prompt。 */
    public static PromptTemplate of(PromptId id, String body) {
        return new PromptTemplate(id, body, Set.of());
    }

    /** 便捷构造：显式声明变量。 */
    public static PromptTemplate of(PromptId id, String body, String... variables) {
        return new PromptTemplate(id, body, new LinkedHashSet<>(List.of(variables)));
    }
}
