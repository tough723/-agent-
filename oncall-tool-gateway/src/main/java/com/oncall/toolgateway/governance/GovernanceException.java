package com.oncall.toolgateway.governance;

import com.oncall.domain.governance.ReviewVerdict;

/**
 * 工具策略治理过程中的失败。
 *
 * <p>刻意携带 {@link ReviewVerdict} 而不是只带一句消息：接入 REST 时，
 * 四种拒绝要映射成<b>不同</b>的状态码（410 / 403 / 409 / 409），
 * 前端的处置也不同——过期要重走流程，自审要换人。
 * 只带消息就得靠字符串匹配来分流，那迟早会错。
 *
 * <p>{@link #NOT_FOUND} 与 {@code FORBIDDEN} 都不是复核判定的一种，
 * 所以那种情况下 {@code verdict} 为 {@code null}——
 * 接入层据此区分「复核判定失败」（有 verdict）与「请求本身不合法」（无 verdict）。
 */
public class GovernanceException extends RuntimeException {

    /** 单子不存在时用的哨兵，不属于 {@link ReviewVerdict} 的五个值。 */
    public static final String NOT_FOUND = "NOT_FOUND";

    private final String code;
    private final ReviewVerdict verdict;

    public GovernanceException(String code, ReviewVerdict verdict, String message) {
        super(message);
        this.code = code;
        this.verdict = verdict;
    }

    public static GovernanceException notFound(String id) {
        return new GovernanceException(NOT_FOUND, null, "变更单不存在：" + id);
    }

    public static GovernanceException badRequest(String message) {
        return new GovernanceException("BAD_REQUEST", null, message);
    }

    /**
     * 身份有效但角色不允许。
     *
     * <p><b>与 {@link #badRequest} 分开，因为 HTTP 语义不同</b>：
     * {@code BAD_REQUEST} 是"请求写错了，改一下重发"，
     * {@code FORBIDDEN} 是"请求没问题，但你没这个权限，换个人"。
     * 前端对两者的处置不同，混成一个码就会把"换人"显示成"参数有误"。
     */
    public static GovernanceException forbidden(String message) {
        return new GovernanceException("FORBIDDEN", null, message);
    }

    public static GovernanceException fromVerdict(ReviewVerdict verdict, String message) {
        return new GovernanceException(verdict.name(), verdict, message);
    }

    public String code() {
        return code;
    }

    /** 复核类失败时非 null；单据不存在 / 请求非法时为 null。 */
    public ReviewVerdict verdict() {
        return verdict;
    }
}
