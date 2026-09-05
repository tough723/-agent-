package com.oncall.config.admin;

/**
 * 统一的错误响应体。
 *
 * @param status  HTTP 状态码，冗余在 body 里是为了让前端不用解析 header 也能分支
 * @param message 面向操作者的说明，<b>会直接显示在界面上</b>，所以文案要说清
 *                "为什么不行"和"该怎么办"，不能只是"参数错误"
 * @param key     相关的配置键；与配置项无关的错误为 {@code null}
 */
public record ApiError(int status, String message, String key) {

    public static ApiError of(int status, String message) {
        return new ApiError(status, message, null);
    }
}
