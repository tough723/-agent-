package com.oncall.agent.prompt;

/**
 * 一个 prompt 的身份：<b>名字 + 版本</b>。
 *
 * <p>版本是身份的一部分，不是它的一个属性。这个区别是承重的：
 * {@code llm_call_log.prompt_version} 之所以能支撑
 * 「改 prompt 之后质量变了」的归因，前提就是<b>一个版本号唯一确定一段正文</b>。
 * 如果版本可以原地修改，历史日志里那个版本号就指向了一段已经不存在的文本，
 * 归因能力当场消失。
 *
 * @param name    prompt 名，形如 {@code intent-classify}。
 *                限定为 {@code [a-z][a-z0-9-]*}：文件名里要用它做前缀，
 *                允许点号会让 {@code a.b.v1.md} 的切分产生歧义。
 * @param version 版本标签，形如 {@code v1}。刻意是 {@code String} 而不是 {@code int}：
 *                {@code llm_call_log.prompt_version} 是 {@code VARCHAR(64)}，
 *                而且将来会出现 {@code v3-canary} 这类灰度标签。
 */
public record PromptId(String name, String version) {

    /** prompt 名的合法形状。 */
    public static final String NAME_PATTERN = "[a-z][a-z0-9-]*";
    /** 版本标签的合法形状：必须以 {@code v} 开头，见 {@link PromptId} 的类注释。 */
    public static final String VERSION_PATTERN = "v[A-Za-z0-9][A-Za-z0-9._-]*";

    public PromptId {
        if (name == null || !name.matches(NAME_PATTERN)) {
            throw new PromptException(PromptException.Kind.MALFORMED_FILENAME,
                    "prompt 名必须匹配 " + NAME_PATTERN + "，收到：" + name);
        }
        if (version == null || !version.matches(VERSION_PATTERN)) {
            throw new PromptException(PromptException.Kind.MALFORMED_FILENAME,
                    "版本标签必须匹配 " + VERSION_PATTERN + "（以 v 开头），收到：" + version);
        }
    }

    /** 对应的资源文件名，形如 {@code intent-classify.v1.md}。 */
    public String fileName() {
        return name + "." + version + ".md";
    }
}
