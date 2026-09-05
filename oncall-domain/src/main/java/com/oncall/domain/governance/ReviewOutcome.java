package com.oncall.domain.governance;

import java.util.Objects;

/**
 * 双人复核的判定结果：一个 {@link ReviewVerdict} + 一句给人看的话。
 *
 * <p>把消息与判定放在一起返回，是因为<b>消息本身有安全含义</b>：
 * {@link ReviewVerdict#STALE} 的提示里含当前生效值，
 * 所以 {@link TwoPersonReview} 必须保证只有判定为 ALLOWED 或 STALE 的调用方
 * 才可能看到它——判定顺序就是那条防线，见 {@link TwoPersonReview#evaluate}。
 *
 * @param verdict 判定
 * @param message 给操作者看的说明。ALLOWED 时为空串而不是 null，
 *                避免调用方到处判空
 */
public record ReviewOutcome(ReviewVerdict verdict, String message) {

    public ReviewOutcome {
        Objects.requireNonNull(verdict, "verdict");
        message = (message == null) ? "" : message;
    }

    public boolean allowed() {
        return verdict == ReviewVerdict.ALLOWED;
    }

    static ReviewOutcome allowed() {
        return new ReviewOutcome(ReviewVerdict.ALLOWED, "");
    }

    static ReviewOutcome deny(ReviewVerdict verdict, String message) {
        if (verdict == ReviewVerdict.ALLOWED) {
            throw new IllegalArgumentException("deny() 不能传 ALLOWED");
        }
        return new ReviewOutcome(verdict, message);
    }
}
