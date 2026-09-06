package com.oncall.agent.prompt;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * prompt 的单一事实来源：<b>按 (名字, 版本) 取正文，绝不静默回退</b>。
 *
 * <p>它要解决的不是"把字符串挪出代码"这种整洁性问题，而是 prompt 一旦散进
 * Java 字符串常量就会同时失去的四样能力：<b>灰度、回滚、归因、评审</b>
 * （《测试与交付保障体系》§2）。其中"归因"是硬需求——
 * {@code llm_call_log.prompt_version} 那一列如果填不出可信的值，
 * 「改 prompt 之后质量变了」就永远无法定位。
 *
 * <p><b>三条设计约束：</b>
 *
 * <p><b>① 版本不存在直接抛。</b>不提供"取最新版"。
 * 自动升版等于让 prompt 变更绕过评审与灰度，而那正是这条机制要防的事。
 *
 * <p><b>② 生效版本在启动时校验存在性。</b>{@link #activeVersion} 会检查
 * 配置指向的版本是否真的在注册表里。指向一个没部署的版本时必须立刻炸——
 * 静默换用别的版本，会让"我们以为在跑 v4"变成"其实在跑 v1"，
 * 而这恰好是灰度期间最难发现的错。
 *
 * <p><b>③ 取文本与取版本号必须是一次操作。</b>见 {@link #renderActiveWithVersion}。
 */
public final class PromptRegistry {

    /** classpath 上 prompt 文件的存放位置。 */
    public static final String RESOURCE_LOCATION = "classpath*:prompts/*.md";

    /** {@code <name>.<version>.md}。两段都收紧到不含点号的形状，切分才没有歧义。 */
    private static final Pattern FILE_NAME = Pattern.compile(
            "(" + PromptId.NAME_PATTERN + ")\\.(" + PromptId.VERSION_PATTERN + ")\\.md");

    private final Map<String, Map<String, PromptTemplate>> byName;
    private final ActiveVersionSource activeVersions;

    public PromptRegistry(Map<PromptId, PromptTemplate> templates, ActiveVersionSource activeVersions) {
        Objects.requireNonNull(templates, "templates");
        this.activeVersions = Objects.requireNonNull(activeVersions, "activeVersions");
        if (templates.isEmpty()) {
            throw new PromptException(PromptException.Kind.EMPTY_REGISTRY,
                    "一个 prompt 都没有装载。这几乎肯定是资源目录或文件名写错了，"
                            + "而不是「这个系统真的不需要 prompt」");
        }
        Map<String, Map<String, PromptTemplate>> grouped = new TreeMap<>();
        for (Map.Entry<PromptId, PromptTemplate> e : templates.entrySet()) {
            PromptTemplate t = Objects.requireNonNull(e.getValue(), "prompt 模板不能为 null");
            if (!t.id().equals(e.getKey())) {
                throw new PromptException(PromptException.Kind.MALFORMED_FILENAME,
                        "注册表的键 " + e.getKey() + " 与模板自身的 id " + t.id() + " 不一致");
            }
            grouped.computeIfAbsent(t.id().name(), k -> new TreeMap<>())
                    .put(t.id().version(), t);
        }
        // 不可变但要保序：Map.copyOf 的迭代顺序不保证，
        // 而 names() / describe() 会进日志与错误消息。
        this.byName = java.util.Collections.unmodifiableMap(new TreeMap<>(grouped));
    }

    /**
     * 从 classpath 的 {@code prompts/} 目录装载。
     *
     * <p><b>版本靠扫描发现，名字必须显式给出。</b>这个组合是刻意的：
     * <ul>
     *   <li>版本手抄一份清单，就是给自己造一个漂移源——加了 {@code v5} 忘了登记，
     *       而"忘了登记"的表现是取不到，不是报错；</li>
     *   <li>名字如果也靠扫描，那么"少了一个 prompt"永远不会被发现。
     *       显式列出后，缺文件会在<b>启动时</b>炸，而不是等第一个请求进来。</li>
     * </ul>
     *
     * @param expectedNames 这个部署应当包含的 prompt 名，一个都不能少
     */
    public static PromptRegistry fromClasspath(Collection<String> expectedNames,
                                               ActiveVersionSource activeVersions) {
        return fromClasspath(RESOURCE_LOCATION, expectedNames, activeVersions);
    }

    /**
     * 同上，但指定扫描位置。
     *
     * <p>存在的理由是<b>让失败路径可测</b>：文件名不合法、同一个版本在 classpath
     * 上出现两次这两种情况，都没法在真实的 {@code prompts/} 目录里构造——
     * 放进去就会让所有其它用例一起炸。所以给测试一个独立的目录。
     */
    public static PromptRegistry fromClasspath(String locationPattern,
                                               Collection<String> expectedNames,
                                               ActiveVersionSource activeVersions) {
        Objects.requireNonNull(locationPattern, "locationPattern");
        Objects.requireNonNull(expectedNames, "expectedNames");
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(locationPattern);
        } catch (IOException e) {
            throw new PromptException(PromptException.Kind.RESOURCE_ERROR,
                    "扫描 " + locationPattern + " 失败", e);
        }

        Map<PromptId, PromptTemplate> loaded = new LinkedHashMap<>();
        for (Resource r : resources) {
            String fileName = r.getFilename();
            if (fileName == null) {
                continue;
            }
            Matcher m = FILE_NAME.matcher(fileName);
            if (!m.matches()) {
                throw new PromptException(PromptException.Kind.MALFORMED_FILENAME,
                        "prompts/ 下的 " + fileName + " 不符合 <name>.<version>.md 约定"
                                + "（name 需匹配 " + PromptId.NAME_PATTERN
                                + "，version 需以 v 开头）。宁可启动失败，"
                                + "也不要让一个 prompt 静默地不在注册表里");
            }
            PromptId id = new PromptId(m.group(1), m.group(2));
            if (loaded.putIfAbsent(id, PromptTemplate.parse(id, read(r))) != null) {
                throw new PromptException(PromptException.Kind.DUPLICATE_VERSION,
                        id.fileName() + " 在 classpath 上出现了不止一次。"
                                + "两个同名文件内容可能不同，随便挑一个等于埋一颗不知道何时爆的雷");
            }
        }

        for (String name : expectedNames) {
            boolean found = loaded.keySet().stream().anyMatch(id -> id.name().equals(name));
            if (!found) {
                throw new PromptException(PromptException.Kind.UNKNOWN_PROMPT,
                        "prompts/ 里找不到 \"" + name + "\" 的任何版本。"
                                + "启动就失败，比第一个请求进来才发现要好");
            }
        }
        return new PromptRegistry(loaded, activeVersions);
    }

    private static String read(Resource r) {
        try (InputStream in = r.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new PromptException(PromptException.Kind.RESOURCE_ERROR,
                    "读取 " + r.getFilename() + " 失败", e);
        }
    }

    // ------------------------------------------------------------------ 读

    /** 这个 prompt 有哪些版本，按字母序。 */
    public Set<String> availableVersions(String name) {
        // TreeSet 而不是 Set.copyOf：同样是顺序问题。
        return java.util.Collections.unmodifiableSortedSet(new TreeSet<>(requireName(name).keySet()));
    }

    /** 装载了哪些 prompt 名。 */
    public Set<String> names() {
        return byName.keySet();
    }

    /**
     * 当前生效版本。<b>会校验该版本确实存在。</b>
     *
     * <p>理由见类注释②：配置指向一个没部署的版本时，
     * 静默换用别的版本会让灰度期间"我们以为在跑 v4，其实在跑 v1"。
     */
    public String activeVersion(String name) {
        Map<String, PromptTemplate> versions = requireName(name);
        String v = activeVersions.activeVersion(name);
        if (!versions.containsKey(v)) {
            throw new PromptException(PromptException.Kind.UNKNOWN_VERSION,
                    "prompt \"" + name + "\" 的生效版本被指定为 \"" + v
                            + "\"，但装载到的版本只有 " + new TreeSet<>(versions.keySet())
                            + "。不会回退到其它版本");
        }
        return v;
    }

    /**
     * 渲染指定版本。
     *
     * @throws PromptException 名字或版本不存在。<b>不会回退到"最新版"。</b>
     */
    public String render(String name, String version, Map<String, Object> vars) {
        return template(name, version).render(vars);
    }

    /** 用当前生效版本渲染，只要文本。 */
    public String renderActive(String name, Map<String, Object> vars) {
        return renderActiveWithVersion(name, vars).text();
    }

    /**
     * 用当前生效版本渲染，<b>同时返回用到的版本号</b>。
     *
     * <p><b>为什么不能"先 {@code renderActive} 拿文本，再 {@code activeVersion} 拿版本号"</b>：
     * 那是两次读取，而生效版本是配置项、可以热切换。
     * 两次读取之间只要发生一次切换，写进 {@code llm_call_log} 的版本
     * 就和真正发出去的那段 prompt 对不上——
     * 归因数据在没人察觉的情况下变成错的，比没有这列更糟。
     * 本项目已经在别处踩过两次"同一个流程读两次可变状态"的坑。
     */
    public Rendered renderActiveWithVersion(String name, Map<String, Object> vars) {
        String version = activeVersion(name);
        return new Rendered(template(name, version).render(vars), name, version);
    }

    /**
     * 一次渲染的结果。
     *
     * @param text    渲染后的正文，直接进 {@code Prompt}
     * @param name    prompt 名，写进 {@code llm_call_log.call_type} 一侧的归因信息
     * @param version 实际用到的版本，<b>必须</b>写进 {@code llm_call_log.prompt_version}
     */
    public record Rendered(String text, String name, String version) {
    }

    // ------------------------------------------------------------------ 内部

    private Map<String, PromptTemplate> requireName(String name) {
        Map<String, PromptTemplate> versions = byName.get(name);
        if (versions == null) {
            throw new PromptException(PromptException.Kind.UNKNOWN_PROMPT,
                    "没有名为 \"" + name + "\" 的 prompt；已装载的是 " + byName.keySet());
        }
        return versions;
    }

    private PromptTemplate template(String name, String version) {
        Map<String, PromptTemplate> versions = requireName(name);
        PromptTemplate t = versions.get(version);
        if (t == null) {
            throw new PromptException(PromptException.Kind.UNKNOWN_VERSION,
                    "prompt \"" + name + "\" 没有版本 \"" + version
                            + "\"；可用的是 " + new TreeSet<>(versions.keySet())
                            + "。刻意不回退到最新版：静默升版会让 prompt 变更绕过评审与灰度");
        }
        return t;
    }

    /** 供测试与自检使用：装载到的模板总数。 */
    public int size() {
        return byName.values().stream().mapToInt(Map::size).sum();
    }

    /** 供测试与自检使用：按名字列出全部版本。 */
    public List<String> describe() {
        return byName.entrySet().stream()
                .map(e -> e.getKey() + " -> " + new TreeSet<>(e.getValue().keySet()))
                .toList();
    }
}
