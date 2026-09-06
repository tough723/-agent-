package com.oncall.toolgateway;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 参数与结果脱敏 —— {@code tool_audit_log.args_masked} / {@code result_masked} 的唯一来源。
 *
 * <p><b>为什么必须有</b>：那两列的名字里带 {@code masked}，DDL 注释写的是
 * 「脱敏后的参数——审计表查询频率最高，不能成为敏感数据的第二个副本」。
 * 而原先 {@code ToolAuditLog.recordSuccess(key, toolName, args, result)} 传的是
 * <b>未脱敏原文</b>。列名说谎是审计里最难发现的一类问题：
 * 读代码的人看到 {@code args_masked} 就假定已经脱敏了，于是审计表可以放心给更多人查——
 * 而它其实是密钥的第二个副本，且保留 180 天。
 *
 * <p><b>三条不可退让的性质</b>：
 *
 * <ol>
 *   <li><b>绝不抛异常。</b>脱敏在审计写入路径上。模型生成的参数可能是残缺 JSON，
 *       如果脱敏因此抛异常，结果就是「这次调用没有审计记录」——
 *       而一次没有审计的高危操作，正是审计存在的理由。
 *       所以任何意外都退回 {@link #FALLBACK}（什么都不透露），而不是抛出去。</li>
 *   <li><b>宁可多遮，不可少遮。</b>判定不了的时候按敏感处理。</li>
 *   <li><b>不保留被遮值的长度。</b>一律替换成固定长度的 {@code ***}，
 *       否则 token 长度本身就是信息。</li>
 * </ol>
 *
 * <p><b>已知局限（明确记录，不假装没有）</b>：按<b>键名</b>判定，
 * 所以「键名无害但值是密钥」的情况遮不住（例如 {@code "note": "sk-..."}）。
 * 要做值特征识别就得引入误报，而误报的代价是审计内容被无差别涂黑、审计表失去价值。
 * 这个取舍是刻意的，真要收紧应当按工具的 {@code argsJsonSchema} 逐字段标注，
 * 而不是靠正则猜。
 *
 * <p><b>刻意做成纯静态、不可注入</b>：一旦可注入，就得给每个部署配一份规则，
 * 而那份规则会变成一个「配了但没人读」的键。等真需要按 schema 逐字段标注时，
 * 它应当升级成策略接口，而不是现在先留一个只有测试会用的构造器。
 */
public final class ArgMasker {

    /** 意外情况下的返回值：什么都不透露。 */
    public static final String FALLBACK = "<masked>";

    /**
     * 脱敏后保留的最大字符数。
     *
     * <p>列类型是 {@code TEXT}，没有硬上限，但审计表是「查询频率最高」的表，
     * 让它存下模型的整段上下文会直接拖慢最常用的查询。
     * 超出部分截断并标注，而不是静默丢弃——静默截断会让人以为看到了完整参数。
     */
    public static final int MAX_MASKED_LENGTH = 8000;

    private static final String TRUNCATED_SUFFIX = "…<truncated>";

    /** 替换值：固定长度，不泄露原值长度。 */
    private static final String MASK = "***";

    /**
     * 命中即视为敏感的<b>单词</b>。
     *
     * <p>刻意用分词后精确匹配而不是子串匹配：子串会让 {@code author} 命中 {@code auth}、
     * {@code keyboard} 命中 {@code key}，把无害字段一起涂黑。
     */
    private static final Set<String> SENSITIVE_TOKENS = Set.of(
            "password", "passwd", "pwd",
            "secret", "token",
            "authorization", "auth",
            "credential", "credentials",
            "cookie", "session", "sessionid",
            "ssn", "idcard",
            "phone", "mobile", "email");

    /**
     * 去掉所有分隔符后的<b>复合词</b>白名单。
     *
     * <p>为什么还要这一层：分词靠驼峰与分隔符，而 {@code apikey} 是全小写连写，
     * 分出来只有一个词 {@code apikey}，既不在单词表里、也凑不出 {@code api}+{@code key}。
     * 把分隔符全部抹掉再比一次，{@code apikey} / {@code api_key} / {@code API-KEY} /
     * {@code apiKEY} 四种写法就都落到同一个键上。
     */
    private static final Set<String> SENSITIVE_COMPOUNDS = Set.of(
            "apikey", "apitoken", "accesstoken", "authtoken",
            "privatekey", "secretkey", "sessiontoken", "idcard");

    /**
     * 需要<b>两个词同时出现</b>才算敏感的组合。
     *
     * <p>{@code key} 单独出现太宽（{@code sortKey}、{@code primaryKey} 都无害），
     * 但 {@code api}+{@code key} 就必须遮。
     */
    private static final List<Set<String>> SENSITIVE_PAIRS = List.of(
            Set.of("api", "key"),
            Set.of("access", "key"),
            Set.of("secret", "key"),
            Set.of("private", "key"),
            Set.of("id", "card"));

    /**
     * 键值对：键可以是 JSON 引号形式或裸标识符，值可以是字符串、单引号串或裸值。
     *
     * <p>用正则而不是 JSON 解析：模型生成的参数<b>不保证是合法 JSON</b>，
     * 而脱敏必须对残缺输入也生效（见类注释第 1 条）。
     * 解析器在残缺输入上会抛异常，那正是最不能发生的情况。
     */
    private static final Pattern KEY_VALUE = Pattern.compile(
            "(\\"(?:[^\\"\\\\]|\\\\.)*\\"|'[^']*'|[A-Za-z_][\\w.-]*)\\s*[:=]\\s*"
                    // 值的分支顺序有意义：完整引号串必须在残缺引号串之前，
                    // 否则前者永远轮不到。残缺分支是兜底——{"password":"hunter2
                    // 这种被截断的输入如果没有它，密钥会原样进审计表。
                    + "(\\"(?:[^\\"\\\\]|\\\\.)*\\""
                    + "|\\"(?:[^\\"\\\\]|\\\\.)*"
                    + "|'(?:[^'\\\\]|\\\\.)*'|'(?:[^'\\\\]|\\\\.)*"
                    // 裸值只允许 JSON 字面量（数字 / true / false / null）。
                    // 刻意排除 { [ " & —— 否则 {"conn":{"password":...}} 里
                    // 外层键的裸值会把整个内层对象吞掉，内层的敏感键根本轮不到被扫描；
                    // & 则是 key=value 形式里必须终止值的地方。
                    + "|[^,}\\]\\s&\\"{}\\[\\]]+)");

    private ArgMasker() {
    }

    /**
     * 脱敏一段参数或结果文本。
     *
     * <p>{@code null} 原样返回 {@code null}（对应可空列），<b>不</b>转成空串——
     * 「没有值」和「有值但被遮了」在审计里必须是两件事。
     *
     * @return 脱敏后的文本；永不返回 {@code null}（除非输入就是 {@code null}），永不抛异常
     */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        try {
            Matcher m = KEY_VALUE.matcher(text);
            StringBuilder out = new StringBuilder(text.length());
            int last = 0;
            while (m.find()) {
                out.append(text, last, m.start());
                String key = unquote(m.group(1));
                if (isSensitive(key)) {
                    // 原样保留键与分隔符，只换值：审计要能看出「这里有个 password 字段」，
                    // 把键一起涂掉就等于把结构信息也丢了。
                    out.append(m.group(1))
                            .append(text, m.start(1) + m.group(1).length(), m.start(2))
                            .append(quoted(m.group(2)) ? quoteLike(m.group(2), MASK) : MASK);
                } else {
                    out.append(m.group());
                }
                last = m.end();
            }
            out.append(text, last, text.length());
            return cap(out.toString());
        } catch (RuntimeException e) {
            // 见类注释第 1 条：这里抛出去的代价是「这次调用没有审计记录」。
            return FALLBACK;
        }
    }

    /** 截断到上限，并明确标注被截断了。 */
    private static String cap(String s) {
        if (s.length() <= MAX_MASKED_LENGTH) {
            return s;
        }
        return s.substring(0, MAX_MASKED_LENGTH - TRUNCATED_SUFFIX.length()) + TRUNCATED_SUFFIX;
    }

    /** 键名是否敏感。 */
    static boolean isSensitive(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        if (SENSITIVE_COMPOUNDS.contains(key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", ""))) {
            return true;
        }
        Set<String> tokens = tokenize(key);
        if (tokens.isEmpty()) {
            return false;
        }
        for (String t : tokens) {
            if (SENSITIVE_TOKENS.contains(t)) {
                return true;
            }
        }
        for (Set<String> pair : SENSITIVE_PAIRS) {
            if (tokens.containsAll(pair)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 分词：先按驼峰切开，再按非字母数字切开，全部小写。
     *
     * <p>所以 {@code apiKey}、{@code api_key}、{@code API-KEY} 都会得到 {@code [api, key]}，
     * 而 {@code author} 得到 {@code [author]}——不会误命中 {@code auth}。
     */
    static Set<String> tokenize(String key) {
        String spaced = key.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        Set<String> out = new LinkedHashSet<>();
        for (String t : Arrays.stream(spaced.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")).toList()) {
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && (s.charAt(0) == '"' || s.charAt(0) == '\'')
                && s.charAt(s.length() - 1) == s.charAt(0)) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    private static boolean quoted(String v) {
        // 两端都要是同一种引号才算「有引号」：残缺输入里可能出现只有前引号的值，
        // 若只看首字符，遮完会凭空补出一个原本不存在的后引号。
        return v.length() >= 2
                && (v.charAt(0) == '"' || v.charAt(0) == '\'')
                && v.charAt(v.length() - 1) == v.charAt(0);
    }

    private static String quoteLike(String original, String replacement) {
        char q = original.charAt(0);
        return q + replacement + q;
    }
}
