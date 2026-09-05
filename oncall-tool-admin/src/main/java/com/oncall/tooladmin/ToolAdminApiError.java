package com.oncall.tooladmin;

/**
 * 统一的错误响应体。
 *
 * @param status  HTTP 状态码，冗余在 body 里是为了让前端不用解析 header 也能分支
 * @param code    机器可读码，前端据此决定"换人"还是"重新发起"
 * @param message 面向操作者的说明，<b>会直接显示在界面上</b>
 * @param tool    相关工具名；与具体工具无关的错误为 {@code null}
 */
public record ToolAdminApiError(int status, String code, String message, String tool) {

    public static ToolAdminApiError of(int status, String code, String message) {
        return new ToolAdminApiError(status, code, message, null);
    }

    public static ToolAdminApiError of(int status, String code, String message, String tool) {
        return new ToolAdminApiError(status, code, message, tool);
    }
}
