package com.oncall.agent.prompt;

/**
 * Prompt 加载与渲染过程中的失败。
 *
 * <p><b>刻意只有一个类型 + 一个 {@link Kind}，而不是一堆异常类</b>：
 * 这些失败全部是<b>装配期或编码期的错误</b>（prompt 文件写错、版本号写错、
 * 变量名打错），调用方不需要针对不同种类走不同分支——
 * 需要的是"立刻炸、并且说清楚是哪个 prompt 的哪个版本出了什么事"。
 * 分成八个异常类只会让 catch 变成一串样板。
 *
 * <p>它们全部是 {@code RuntimeException}：启动自检阶段抛出即启动失败
 * （与 {@code ConfigRegistry} 的「坏声明直接启动失败」是同一条哲学），
 * 运行期抛出说明有人绕过了启动自检。
 */
public final class PromptException extends RuntimeException {

    /** 失败的种类。写进消息，也留给将来可能的监控分类。 */
    public enum Kind {
        /** 注册表里根本没有这个 prompt 名。 */
        UNKNOWN_PROMPT,
        /** 有这个 prompt，但没有这个版本。<b>绝不静默回退到"最新版"。</b> */
        UNKNOWN_VERSION,
        /** 模板正文里出现了未声明的 {@code {{占位符}}}。 */
        UNDECLARED_PLACEHOLDER,
        /** 声明了变量但正文里从未出现——声明已经过期。 */
        STALE_DECLARATION,
        /** 渲染时少传了已声明的变量。 */
        MISSING_VARIABLE,
        /** 渲染时多传了未声明的变量（通常是变量名打错）。 */
        UNEXPECTED_VARIABLE,
        /** 变量的值是 {@code null}——直接拼进 prompt 会变成字面量 "null"。 */
        NULL_VARIABLE,
        /** 一个 prompt 都没加载到。 */
        EMPTY_REGISTRY,
        /** prompt 文件名不符合 {@code <name>.<version>.md} 约定。 */
        MALFORMED_FILENAME,
        /** 同一个 (name, version) 在 classpath 上出现了两次。 */
        DUPLICATE_VERSION,
        /** front-matter 缺失、格式不对，或含有无法识别的键。 */
        MALFORMED_HEADER,
        /** 读取 classpath 资源失败。 */
        RESOURCE_ERROR
    }

    private final Kind kind;

    public PromptException(Kind kind, String message) {
        super("[" + kind + "] " + message);
        this.kind = kind;
    }

    public PromptException(Kind kind, String message, Throwable cause) {
        super("[" + kind + "] " + message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
