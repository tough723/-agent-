package com.oncall.agent.prompt;

import java.util.Map;
import java.util.Objects;

/**
 * 「某个 prompt 当前生效的是哪个版本」。
 *
 * <p>抽成接口而不是让 {@link PromptRegistry} 直接读配置，是为了两件事：
 * <ul>
 *   <li><b>可测</b>：测试里换一个 map 就能模拟"切版本"，不需要起配置中心；</li>
 *   <li><b>可灰度</b>：真正的实现可以按 {@code run_id} 的 hash 分桶返回不同版本
 *       （《测试与交付保障体系》§4.2 的"行为类配置强制灰度"就是这么落的）。
 *       如果这里写死成"读某个配置键"，灰度就没有插入点了。</li>
 * </ul>
 *
 * <p>返回 {@code String} 而不是 {@code int}，与
 * {@code llm_call_log.prompt_version}（{@code VARCHAR(64)}）保持一致，
 * 也让 {@code v3-canary} 这类灰度标签成为可能。
 */
@FunctionalInterface
public interface ActiveVersionSource {

    /**
     * @param promptName prompt 名
     * @return 当前生效的版本标签
     * @throws PromptException 名字未知时。刻意抛而不是返回 {@code null}——
     *                         返回 null 会让上层在某处 NPE，而栈里看不出是配置漏了一项。
     */
    String activeVersion(String promptName);

    /**
     * 固定映射。**不是"取最新版"的替代品**：
     * 没有列进去的名字会抛异常，而不是悄悄挑一个版本用。
     *
     * <p>之所以不提供"取最新版"的默认实现，见 {@link PromptRegistry#activeVersion}：
     * 自动升版会让 prompt 变更绕过评审与灰度。
     */
    static ActiveVersionSource fixed(Map<String, String> versions) {
        Objects.requireNonNull(versions, "versions");
        Map<String, String> copy = Map.copyOf(versions);
        return promptName -> {
            String v = copy.get(promptName);
            if (v == null) {
                throw new PromptException(PromptException.Kind.UNKNOWN_PROMPT,
                        "没有为 prompt \"" + promptName + "\" 指定生效版本；"
                                + "已指定的是 " + new java.util.TreeSet<>(copy.keySet()));
            }
            return v;
        };
    }
}
