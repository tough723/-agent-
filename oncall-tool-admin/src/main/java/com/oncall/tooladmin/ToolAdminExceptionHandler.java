package com.oncall.tooladmin;

import com.oncall.toolgateway.governance.GovernanceException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 把内部异常翻译成 HTTP 响应。
 *
 * <p><b>四种复核拒绝刻意映射到不同状态码</b>，因为前端的处置不同：
 * <ul>
 *   <li>{@code EXPIRED} → 410：重新发起</li>
 *   <li>{@code NOT_AUTHORIZED} → 403：换人</li>
 *   <li>{@code SELF_APPROVAL} → 409 {@code SELF_APPROVAL}：换人</li>
 *   <li>{@code STALE} → 409 {@code STALE}：重新发起</li>
 * </ul>
 * 合成一个 400 就会在界面上变成同一句"操作失败"，
 * 而"换人"和"重新发起"是两件完全不同的事。
 *
 * <p>{@code assignableTypes} 限定只处理本控制器：
 * 两个接入层各带一个 advice 时，不加限定会互相抢异常。
 */
@RestControllerAdvice(assignableTypes = ToolPolicyAdminController.class)
public class ToolAdminExceptionHandler {

    @ExceptionHandler(ToolAdminApiException.class)
    public ResponseEntity<ToolAdminApiError> onApi(ToolAdminApiException e) {
        return ResponseEntity.status(e.status())
                .body(ToolAdminApiError.of(e.status(), e.code(), e.getMessage()));
    }

    /**
     * 治理层的失败。{@link GovernanceException} 携带判定结果，
     * 这里只做映射——<b>判定逻辑不在这一层</b>。
     */
    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<ToolAdminApiError> onGovernance(GovernanceException e) {
        if (GovernanceException.NOT_FOUND.equals(e.code())) {
            return ResponseEntity.status(404)
                    .body(ToolAdminApiError.of(404, e.code(), e.getMessage()));
        }
        if (e.verdict() == null) {
            // 没有 verdict ⇒ 不是复核判定失败，而是请求本身的问题。
            // 再分两种：角色不够是 403（换人），其余是 400（改参数重发）。
            int status = "FORBIDDEN".equals(e.code()) ? 403 : 400;
            return ResponseEntity.status(status)
                    .body(ToolAdminApiError.of(status, e.code(), e.getMessage()));
        }
        int status = switch (e.verdict()) {
            case EXPIRED -> 410;
            case NOT_AUTHORIZED -> 403;
            case SELF_APPROVAL, STALE -> 409;
            case ALLOWED -> 500;   // 不可能：ALLOWED 不会抛异常
        };
        return ResponseEntity.status(status)
                .body(ToolAdminApiError.of(status, e.code(), e.getMessage()));
    }

    /** {@code IllegalArgumentException} 来自领域记录的构造校验，是调用方的问题。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ToolAdminApiError> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest()
                .body(ToolAdminApiError.of(400, "BAD_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ToolAdminApiError> onUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest()
                .body(ToolAdminApiError.of(400, "BAD_REQUEST", "请求体不是合法的 JSON，或缺少必需字段"));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ToolAdminApiError> onMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest()
                .body(ToolAdminApiError.of(400, "BAD_REQUEST", "缺少必需参数：" + e.getParameterName()));
    }
}
