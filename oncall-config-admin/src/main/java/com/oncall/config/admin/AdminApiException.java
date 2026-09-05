package com.oncall.config.admin;

/**
 * 管理接口的业务异常，直接携带 HTTP 状态码。
 *
 * <p>用一个类而不是异常继承树：这些异常的唯一差别就是状态码，
 * 没有需要分别捕获的行为差异。继承树只会让 handler 变长。
 *
 * <p>状态码的用法有一处不直观，见 {@link #notFound(String)}。
 */
public final class AdminApiException extends RuntimeException {

    private final int status;

    public AdminApiException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public static AdminApiException unauthorized(String message) {
        return new AdminApiException(401, message);
    }

    public static AdminApiException forbidden(String message) {
        return new AdminApiException(403, message);
    }

    /**
     * 404 而不是 403，这一点是刻意的。
     *
     * <p>对 BACKEND_ONLY 的键返回 403 等于告诉调用方"这个键存在，只是你没权限"。
     * 而兜底参数与凭据的<b>存在性本身就是信息</b>——攻击者可以据此枚举出
     * 系统有哪些兜底开关。所以对外表现必须与"这个键根本不存在"完全一致。
     */
    public static AdminApiException notFound(String key) {
        return new AdminApiException(404, "配置项不存在：" + key);
    }

    /** 自定义文案的 404，用于待复核单等不是"配置键"的资源。 */
    public static AdminApiException notFoundMessage(String message) {
        return new AdminApiException(404, message);
    }

    public static AdminApiException badRequest(String message) {
        return new AdminApiException(400, message);
    }

    public static AdminApiException conflict(String message) {
        return new AdminApiException(409, message);
    }

    public static AdminApiException gone(String message) {
        return new AdminApiException(410, message);
    }
}
