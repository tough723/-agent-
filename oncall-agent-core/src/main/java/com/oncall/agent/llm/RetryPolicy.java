package com.oncall.agent.llm;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * 单个模型上的重试策略。
 *
 * <p><b>「可重试」为什么是调用方传进来的谓词，而不是这里内置一份判断</b>：
 *
 * <p>能不能重试取决于异常到底是不是瞬时的——429 限流是，401 密钥错不是，
 * 400 请求体不合法更不是。而<b>我们此刻并不知道接入 DashScope 之后
 * 429 会以什么异常类型出现</b>：可能是 Spring AI 的 {@code TransientAiException}，
 * 可能是 SAA 自己包的类型，也可能就是一个裸的 HTTP 客户端异常。
 *
 * <p>猜错的两种后果都不轻：
 * <ul>
 *   <li>判得太严 ⇒ 该重试的没重试，表现为"偶发失败"，最难排查；</li>
 *   <li>判得太松 ⇒ 对一个 401 带着退避重试三次，
 *       每个模型白等几秒，整条 failover 链的延迟被放大数倍——
 *       而这恰好发生在"主模型已经不正常"的时候。</li>
 * </ul>
 *
 * <p>所以这里只提供机制不提供判断，默认策略是<b>不重试、直接 failover</b>：
 * 手上有一条模型链时，换下一个模型永远比在原地等更划算。
 * 真正的分类器等接上真实模型、看到真实异常之后再写——
 * 这条已记在 {@code DEVLONG.md} 的待办里。
 *
 * @param maxAttemptsPerModel   每个模型最多尝试几次，&ge; 1
 * @param initialBackoffMillis  首次重试前的等待，&ge; 0
 * @param backoffMultiplier     退避倍数，&ge; 1.0（小于 1 会让等待越来越短，
 *                              在限流场景下等于加压）
 * @param maxBackoffMillis      单次等待上限，&ge; {@code initialBackoffMillis}
 * @param retryable             判定某个异常是否值得在<b>同一个模型</b>上重试
 */
public record RetryPolicy(
        int maxAttemptsPerModel,
        long initialBackoffMillis,
        double backoffMultiplier,
        long maxBackoffMillis,
        Predicate<Throwable> retryable
) {

    public RetryPolicy {
        if (maxAttemptsPerModel < 1) {
            throw new IllegalArgumentException("maxAttemptsPerModel 至少为 1，收到 " + maxAttemptsPerModel);
        }
        if (initialBackoffMillis < 0) {
            throw new IllegalArgumentException("initialBackoffMillis 不能为负：" + initialBackoffMillis);
        }
        if (backoffMultiplier < 1.0d) {
            throw new IllegalArgumentException(
                    "backoffMultiplier 必须 >= 1.0，否则等待会越来越短，在限流场景下等于加压：" + backoffMultiplier);
        }
        if (maxBackoffMillis < initialBackoffMillis) {
            throw new IllegalArgumentException("maxBackoffMillis 不能小于 initialBackoffMillis："
                    + maxBackoffMillis + " < " + initialBackoffMillis);
        }
        Objects.requireNonNull(retryable, "retryable");
    }

    /**
     * 默认策略：每个模型只试一次，失败立刻换下一个。
     *
     * <p>不是偷懒——这是在"异常分类未知"时唯一不会把事情弄糟的选择。
     * 见类注释。
     */
    public static RetryPolicy failoverOnly() {
        return new RetryPolicy(1, 0L, 1.0d, 0L, t -> false);
    }

    /** 带指数退避的重试策略。分类器必须由调用方给，这里不猜。 */
    public static RetryPolicy withBackoff(int maxAttempts, Predicate<Throwable> retryable) {
        return new RetryPolicy(maxAttempts, 500L, 2.0d, 8_000L, retryable);
    }

    /** 第 {@code attempt} 次失败之后要等多久（1 表示第一次尝试，尚未发生重试）。 */
    public long backoffBeforeRetry(int attempt) {
        if (attempt < 1) {
            return initialBackoffMillis;
        }
        double millis = initialBackoffMillis;
        for (int i = 1; i < attempt; i++) {
            millis = Math.min(millis * backoffMultiplier, maxBackoffMillis);
        }
        return (long) millis;
    }
}
