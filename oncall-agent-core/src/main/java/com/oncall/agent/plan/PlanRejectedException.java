package com.oncall.agent.plan;

/**
 * 计划被静态校验拒绝。
 *
 * <p><b>刻意不携带原始计划内容</b>：计划里的 {@code argsJson} 可能含
 * 来自日志的未净化文本，而这条异常最终会进日志。
 * 需要看内容时应当走 {@code ArgMasker}，不是直接拼进异常消息。
 */
public class PlanRejectedException extends RuntimeException {

    private final int seq;
    private final Reason reason;

    /** 拒绝原因。枚举而非字符串：调用方要能按原因分流处置。 */
    public enum Reason {
        /** 动作不在工具白名单内。 */
        TOOL_NOT_ALLOWED,
        /** 高危步骤没有可信依据（只有日志文本或工具输出）。 */
        NO_TRUSTED_BASIS,
        /** 高危步骤之前的信息收集步骤不足。 */
        INSUFFICIENT_INVESTIGATION
    }

    public PlanRejectedException(int seq, Reason reason, String message) {
        this(seq, reason, message, null);
    }

    /**
     * 带原因链的构造。用于把底层的 {@code ToolDeniedException} 串起来——
     * 否则「哪个工具不在白名单」这个最有诊断价值的信息会丢。
     */
    public PlanRejectedException(int seq, Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.seq = seq;
        this.reason = reason;
    }

    /** 出错步骤的序号，从 1 开始。 */
    public int seq() {
        return seq;
    }

    public Reason reason() {
        return reason;
    }
}
