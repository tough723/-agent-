package com.oncall.agent.llm;

/**
 * failover 链上的模型全部失败。
 *
 * <p><b>刻意是一个独立的类型，而不是把最后一个异常直接抛出去</b>：
 * 调用方（编排层）要能一句话判断"是不是该走规则兜底了"。
 * 如果抛的是最后一个模型的原异常，编排层就得靠 {@code instanceof} 一串
 * 供应商特定的类型来分流——那串类型每换一个模型就要改一次。
 *
 * <p>{@code fallback.model-failover-chain} 的注释写的是
 * 「全部耗尽后进入规则兜底」，这个异常就是那个"耗尽"的信号。
 */
public final class ModelExhaustedException extends RuntimeException {

    private final int modelCount;

    public ModelExhaustedException(int modelCount, Throwable lastFailure) {
        super("failover 链上的 " + modelCount + " 个模型全部失败，最后一次的错误见 cause", lastFailure);
        this.modelCount = modelCount;
    }

    /** 链上有几个模型被试过。写进告警文案用——1 个和 5 个全挂是两件不同的事。 */
    public int modelCount() {
        return modelCount;
    }
}
