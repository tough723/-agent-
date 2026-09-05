package com.oncall.config.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把内部异常翻译成 HTTP 响应。
 *
 * <p>存在的意义不只是"少写几个 try"，而是保证<b>错误文案的质量一致</b>：
 * 配置界面的报错会被非开发人员看到，"参数错误"这种文案等于没说。
 * 统一出口才能保证每条错误都讲清楚原因和下一步。
 */
@RestControllerAdvice(assignableTypes = ConfigAdminController.class)
public class ConfigAdminExceptionHandler {

    @ExceptionHandler(AdminApiException.class)
    public ResponseEntity<ApiError> onAdminApi(AdminApiException e) {
        return ResponseEntity.status(e.status()).body(ApiError.of(e.status(), e.getMessage()));
    }

    /** 请求体不是合法 JSON。400 而不是 500——这是调用方的问题。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> onUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "请求体不是合法的 JSON，或缺少必需字段"));
    }

    /** 缺少必需的查询参数，例如 DELETE 时没带 reason。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> onMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(400, "缺少必需参数：" + e.getParameterName()));
    }
}
