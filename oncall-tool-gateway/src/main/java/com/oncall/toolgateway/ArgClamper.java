package com.oncall.toolgateway;

/**
 * 参数夹紧器（Strategy 模式）。
 *
 * <p><b>核心思想：模型只提方向，不定数值。</b>
 * 即使模型被提示注入骗了，也造不出破坏性参数——例如注入让模型生成 {@code replicas: 0}，
 * 夹紧后会被限制到 {@code minReplicas}，并留下审计记录。
 *
 * <p>这是确定性防线，不依赖"模型是否听话"。
 */
@FunctionalInterface
public interface ArgClamper {

    /**
     * 校验并夹紧参数。
     *
     * @param toolName 工具名，便于日志与审计
     * @param rawArgs  模型生成的原始 JSON 参数
     * @return 夹紧后的 JSON 参数；实现应在发生夹紧时记录审计
     * @throws IllegalArgumentException 参数非法且无法夹紧时
     */
    String clamp(String toolName, String rawArgs);

    /** 不做任何处理的默认实现，用于只读工具。 */
    ArgClamper NOOP = (toolName, rawArgs) -> rawArgs;
}
