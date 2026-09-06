package com.oncall.eval;

import com.oncall.agent.query.Intent;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 意图标注集。
 *
 * <p><b>装载时严格校验，任何一条坏用例都让整个集子装载失败。</b>
 * 理由与 {@code ConfigRegistry} 的「坏声明直接启动失败」是同一条：
 * 评测集是<b>判据的来源</b>，一条被静默跳过的用例意味着
 * 门槛的分母悄悄变小了——而分母变小的表现是"分数变好"，
 * 这是所有失败模式里最坏的一种。
 */
public final class IntentGoldenSet {

    private final String id;
    private final Map<String, IntentCase> byId;

    private IntentGoldenSet(String id, Map<String, IntentCase> byId) {
        this.id = id;
        this.byId = Map.copyOf(byId);
    }

    /** 从 classpath 装载，例如 {@code golden-set/intent/intent-v1.yaml}。 */
    public static IntentGoldenSet load(String resourcePath) {
        Objects.requireNonNull(resourcePath, "resourcePath");
        try (InputStream in = IntentGoldenSet.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("classpath 上找不到标注集：" + resourcePath);
            }
            // 用无参 Yaml 而不是 SafeConstructor：后者的构造签名在 snakeyaml 1.x 与 2.x
            // 之间不一致，写死任一个都会在另一个版本上编译失败，而本模块拿到的版本
            // 由 BOM 决定、不由本模块决定。输入是 Git 里评审过的文件，
            // 不存在不可信输入这条路径；下面仍然逐字段校验结构。
            return parse(resourcePath, new Yaml().load(in));
        } catch (IOException e) {
            throw new IllegalStateException("读取标注集失败：" + resourcePath, e);
        }
    }

    private static IntentGoldenSet parse(String resourcePath, Object root) {
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(resourcePath + " 的顶层应当是一个映射");
        }
        Object setId = map.get("id");
        if (!(setId instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(resourcePath + " 缺少顶层 id");
        }
        Object rawCases = map.get("cases");
        if (!(rawCases instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException(resourcePath + " 的 cases 为空——"
                    + "一个空的标注集会让门槛的分母变成 0，必须当成错误");
        }

        Map<String, IntentCase> byId = new LinkedHashMap<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) {
                throw new IllegalArgumentException(resourcePath + " 里有一条用例不是映射：" + o);
            }
            IntentCase c = new IntentCase(
                    asText(resourcePath, m, "id"),
                    asText(resourcePath, m, "group"),
                    parseExpect(resourcePath, asText(resourcePath, m, "expect")),
                    asText(resourcePath, m, "text"));
            if (byId.putIfAbsent(c.id(), c) != null) {
                throw new IllegalArgumentException(resourcePath + " 里 id 重复：" + c.id()
                        + "。重复 id 会让漏判报告指向两条不同的用例");
            }
        }
        return new IntentGoldenSet(s, byId);
    }

    private static String asText(String resourcePath, Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException(resourcePath + " 里有用例缺少字段 \"" + key + "\"：" + m);
        }
        return String.valueOf(v);
    }

    private static Intent parseExpect(String resourcePath, String raw) {
        return Intent.parse(raw).orElseThrow(() -> new IllegalArgumentException(
                resourcePath + " 里的 expect \"" + raw + "\" 不在意图闭集内，合法值是 "
                        + Arrays.toString(Intent.values())
                        + "。标注集里出现闭集外的标签，说明标注规则已经和代码脱节了"));
    }

    /** 集子标识。 */
    public String id() {
        return id;
    }

    /** 全部用例，按文件里的顺序。 */
    public List<IntentCase> cases() {
        return List.copyOf(byId.values());
    }

    public int size() {
        return byId.size();
    }

    /** 按分组取用例。 */
    public List<IntentCase> byGroup(String group) {
        return byId.values().stream().filter(c -> c.group().equals(group)).toList();
    }

    /** 按标注意图取用例。 */
    public List<IntentCase> withExpected(Intent expect) {
        return byId.values().stream().filter(c -> c.expect() == expect).toList();
    }

    /** 出现过的分组名，按首次出现顺序。 */
    public List<String> groups() {
        List<String> out = new ArrayList<>();
        for (IntentCase c : byId.values()) {
            if (!out.contains(c.group())) {
                out.add(c.group());
            }
        }
        return out;
    }
}
