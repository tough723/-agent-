package com.oncall.tooladmin;

/**
 * 工具治理接口的业务异常，直接携带 HTTP 状态码。
 *
 * <p>刻意与 {@code com.oncall.config.admin.AdminApiException} <b>不共用</b>：
 * 复用它会要求 {@code tool-admin -> config-admin} 的依赖，
 * 而两个模块各自带一个 {@code @RestControllerAdvice}，
 * 同时在 classpath 上时哪个先匹配是不确定的。
 * 两个接入层各管自己的错误，比省这二十行重要。
 */
public final class ToolAdminApiException extends RuntimeException {

    private final int status;
    private final String code;

    public ToolAdminApiException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    /** 机器可读的错误码，前端据此分支；文案可以改，码不能改。 */
    public String code() {
        return code;
    }

    public static ToolAdminApiException unauthorized(String message) {
        return new ToolAdminApiException(401, "UNAUTHORIZED", message);
    }

    public static ToolAdminApiException forbidden(String message) {
        return new ToolAdminApiException(403, "FORBIDDEN", message);
    }

    public static ToolAdminApiException notFound(String message) {
        return new ToolAdminApiException(404, "NOT_FOUND", message);
    }

    public static ToolAdminApiException badRequest(String message) {
        return new ToolAdminApiException(400, "BAD_REQUEST", message);
    }

    public static ToolAdminApiException conflict(String code, String message) {
        return new ToolAdminApiException(409, code, message);
    }

    public static ToolAdminApiException gone(String message) {
        return new ToolAdminApiException(410, "EXPIRED", message);
    }
}
