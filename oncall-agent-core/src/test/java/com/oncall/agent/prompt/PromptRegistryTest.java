package com.oncall.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * prompt 注册表：版本寻址、生效版本、classpath 装载。
 *
 * <p>这一层最该被守住的一条是<b>「版本不存在绝不静默回退」</b>。
 * 回退到"最新版"看起来很友好，但它会让 prompt 变更绕过评审与灰度——
 * 而这正是整个版本机制要防的事。所以有好几条用例断言的是<b>必须抛</b>。
 */
@DisplayName("PromptRegistry：版本寻址与 classpath 装载")
class PromptRegistryTest {

    // ------------------------------------------------------------------ 版本寻址

    @Test
    @DisplayName("按 (名字, 版本) 取到对应正文")
    void rendersTheRequestedVersion() {
        PromptRegistry r = registry(ActiveVersionSource.fixed(Map.of("demo", "v1")),
                tpl("demo", "v1", "这是 v1"),
                tpl("demo", "v2", "这是 v2"));

        assertThat(r.render("demo", "v1", Map.of())).isEqualTo("这是 v1");
        assertThat(r.render("demo", "v2", Map.of())).isEqualTo("这是 v2");
    }

    @Test
    @DisplayName("版本不存在 → 抛，并且<b>不</b>回退到最新版")
    void unknownVersionThrowsInsteadOfFallingBack() {
        PromptRegistry r = registry(ActiveVersionSource.fixed(Map.of("demo", "v2")),
                tpl("demo", "v1", "v1"),
                tpl("demo", "v2", "v2"));

        assertThatThrownBy(() -> r.render("demo", "v3", Map.of()))
                .isInstanceOf(PromptException.class)
                .extracting(e -> ((PromptException) e).kind())
                .isEqualTo(PromptException.Kind.UNKNOWN_VERSION);
        assertThat(r.availableVersions("demo")).containsExactly("v1", "v2");
    }

    @Test
    @DisplayName("prompt 名不存在 → 抛，并在消息里列出已装载的名字")
    void unknownPromptThrows() {
        PromptRegistry r = registry(ActiveVersionSource.fixed(Map.of("demo", "v1")),
                tpl("demo", "v1", "v1"));

        assertThatThrownBy(() -> r.render("planner", "v1", Map.of()))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("planner")
                .hasMessageContaining("demo");
    }

    @Test
    @DisplayName("生效版本来自 ActiveVersionSource，所以可以热切换")
    void activeVersionComesFromTheSource() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("demo", "v1");
        PromptRegistry r = registry(ActiveVersionSource.fixed(config),
                tpl("demo", "v1", "v1 的正文"),
                tpl("demo", "v2", "v2 的正文"));

        assertThat(r.activeVersion("demo")).isEqualTo("v1");
        assertThat(r.renderActive("demo", Map.of())).isEqualTo("v1 的正文");
    }

    @Test
    @DisplayName("生效版本指向一个没装载的版本 → 抛（不会悄悄换用别的版本）")
    void activeVersionPointingAtAnUndeployedVersionThrows() {
        PromptRegistry r = registry(ActiveVersionSource.fixed(Map.of("demo", "v9")),
                tpl("demo", "v1", "v1"));

        // 灰度期间最容易出的错：以为在跑 v9，其实在跑 v1
        assertThatThrownBy(() -> r.renderActive("demo", Map.of()))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("v9")
                .hasMessageContaining("不会回退");
    }

    @Test
    @DisplayName("renderActiveWithVersion 一次返回文本与版本号")
    void renderActiveWithVersionReturnsBoth() {
        PromptRegistry r = registry(ActiveVersionSource.fixed(Map.of("demo", "v2")),
                tpl("demo", "v1", "v1"),
                tpl("demo", "v2", "你好 {{name}}", "name"));

        PromptRegistry.Rendered out = r.renderActiveWithVersion("demo", Map.of("name", "张三"));

        assertThat(out.text()).isEqualTo("你好 张三");
        assertThat(out.version()).isEqualTo("v2");
        assertThat(out.name()).isEqualTo("demo");
    }

    // ------------------------------------------------------------------ 构造校验

    @Test
    @DisplayName("一个 prompt 都没有 → 抛（几乎肯定是资源目录或文件名写错了）")
    void emptyRegistryIsRejected() {
        assertThatThrownBy(() -> new PromptRegistry(Map.of(), ActiveVersionSource.fixed(Map.of())))
                .isInstanceOf(PromptException.class)
                .extracting(e -> ((PromptException) e).kind())
                .isEqualTo(PromptException.Kind.EMPTY_REGISTRY);
    }

    @Test
    @DisplayName("注册表的键与模板自身的 id 不一致 → 抛")
    void keyTemplateMismatchIsRejected() {
        PromptTemplate t = tpl("demo", "v1", "正文");
        Map<PromptId, PromptTemplate> bad = Map.of(new PromptId("other", "v1"), t);

        assertThatThrownBy(() -> new PromptRegistry(bad, ActiveVersionSource.fixed(Map.of())))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("不一致");
    }

    @Test
    @DisplayName("ActiveVersionSource.fixed 对没列进去的名字抛，而不是随便挑一个版本")
    void fixedSourceRejectsUnknownNames() {
        assertThatThrownBy(() -> ActiveVersionSource.fixed(Map.of("demo", "v1"))
                .activeVersion("planner"))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("planner");
    }

    @Test
    @DisplayName("names 与 availableVersions 反映装载结果")
    void namesAndVersionsAreVisible() {
        PromptRegistry r = registry(ActiveVersionSource.fixed(Map.of("demo", "v1")),
                tpl("demo", "v1", "v1"),
                tpl("demo", "v2", "v2"),
                tpl("static", "v1", "s"));

        assertThat(r.names()).containsExactly("demo", "static");
        assertThat(r.availableVersions("demo")).containsExactly("v1", "v2");
        assertThat(r.size()).isEqualTo(3);
        assertThat(r.describe()).containsExactly("demo -> [v1, v2]", "static -> [v1]");
    }

    // ------------------------------------------------------------------ classpath 装载

    @Test
    @DisplayName("从 classpath 装载：版本靠扫描发现，并读到生产的 intent-classify.v1")
    void fromClasspathDiscoversVersionsIncludingTheProductionPrompt() {
        PromptRegistry r = PromptRegistry.fromClasspath(
                List.of("intent-classify", "demo", "static"),
                ActiveVersionSource.fixed(Map.of("intent-classify", "v1")));

        // 生产 prompt 真的被发现并且能通过白名单校验——
        // 解析失败会直接抛，所以这条同时是那个 .md 文件的格式测试。
        assertThat(r.availableVersions("intent-classify")).containsExactly("v1");
        assertThat(r.availableVersions("demo")).containsExactly("v1", "v2");
        assertThat(r.availableVersions("static")).containsExactly("v1");

        String rendered = r.renderActive("intent-classify", Map.of(
                "conversation", "（无历史）", "question", "那这个服务上次也这样吗？"));
        assertThat(rendered)
                .contains("（无历史）")
                .contains("那这个服务上次也这样吗？")
                .as("占位符必须被替换掉，不能留字面量")
                .doesNotContain("{{conversation}}")
                .doesNotContain("{{question}}");
        assertThat(rendered).as("JSON 示例要原样保留").contains("\"intent\": \"QUERY_HISTORY\"");
    }

    @Test
    @DisplayName("少了一个期望的 prompt → 启动就失败，而不是等第一个请求进来")
    void fromClasspathFailsWhenAnExpectedNameIsMissing() {
        assertThatThrownBy(() -> PromptRegistry.fromClasspath(
                List.of("demo", "planner"),
                ActiveVersionSource.fixed(Map.of("demo", "v1"))))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("planner")
                .hasMessageContaining("启动就失败");
    }

    @Test
    @DisplayName("文件名不符合 <name>.<version>.md → 抛，不让一个 prompt 静默缺席")
    void fromClasspathRejectsMalformedFilenames() {
        assertThatThrownBy(() -> PromptRegistry.fromClasspath(
                "classpath*:prompt-bad/*.md", List.of(),
                ActiveVersionSource.fixed(Map.of())))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("not-a-prompt.md")
                .hasMessageContaining("不符合");
    }

    @Test
    @DisplayName("同一个 (name, version) 在 classpath 上出现两次 → 抛")
    void fromClasspathRejectsDuplicateVersions() {
        // 两个目录里各有一个 dup.v1.md，内容不同。
        // 随便挑一个等于埋一颗不知道何时爆的雷。
        assertThatThrownBy(() -> PromptRegistry.fromClasspath(
                "classpath*:prompt-dup-*/*.md", List.of(),
                ActiveVersionSource.fixed(Map.of())))
                .isInstanceOf(PromptException.class)
                .extracting(e -> ((PromptException) e).kind())
                .isEqualTo(PromptException.Kind.DUPLICATE_VERSION);
    }

    @Test
    @DisplayName("渲染时变量不匹配的错误会带上文件名，方便定位是哪一版 prompt")
    void renderErrorsIdentifyTheOffendingFile() {
        PromptRegistry r = registry(ActiveVersionSource.fixed(Map.of("demo", "v1")),
                tpl("demo", "v1", "你好 {{name}}", "name"));

        // 少一个 name、多一个 nmae 是同一个拼写错误的两半，消息里两个都要出现
        assertThatThrownBy(() -> r.render("demo", "v1", Map.of("nmae", "张三")))
                .isInstanceOf(PromptException.class)
                .hasMessageContaining("demo.v1.md")
                .hasMessageContaining("nmae")
                .hasMessageContaining("name");
    }

    // ------------------------------------------------------------------ helpers

    private static PromptTemplate tpl(String name, String version, String body, String... vars) {
        return PromptTemplate.of(new PromptId(name, version), body, vars);
    }

    private static PromptRegistry registry(ActiveVersionSource source, PromptTemplate... templates) {
        Map<PromptId, PromptTemplate> map = new LinkedHashMap<>();
        for (PromptTemplate t : templates) {
            map.put(t.id(), t);
        }
        return new PromptRegistry(map, source);
    }
}
