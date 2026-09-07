package com.oncall.agent.llm;

/**
 * 模型调用计量的落库端口。
 *
 * <p><b>为什么是端口而不是直接写库</b>：F11 禁止 {@code com.oncall.agent.llm..}
 * 依赖 domain / config / toolgateway / ontology。计量本身是传输层的事实，
 * 不该为了写一行日志就把领域层拖进依赖图——
 * 否则 knowledge 模块将来想复用同一个 {@code ResilientChatModel}，
 * 就会顺带继承一堆它不需要的依赖。
 *
 * <p><b>写失败该怎么办</b>：实现必须把异常抛出来，<b>不得吞掉</b>。
 * 计量丢失是静默的——成本报表只会给出一个偏小的数，
 * 而没人会怀疑一张看起来正常的表。宁可让调用方看见失败。
 *
 * @see LlmCallRecord
 * @see CallOutcome
 */
public interface LlmCallLog {

    /**
     * 落一行计量。
     *
     * @throws RuntimeException 落库失败时抛出，<b>不得吞掉</b>
     */
    void record(LlmCallRecord record);
}
