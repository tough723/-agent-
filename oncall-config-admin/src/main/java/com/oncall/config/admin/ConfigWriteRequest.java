package com.oncall.config.admin;

/**
 * 配置写入请求体。
 *
 * @param value  新值；{@code null} 或空串表示恢复默认值
 * @param reason 变更理由，<b>必填</b>
 */
public record ConfigWriteRequest(String value, String reason) {

    public boolean isResetRequest() {
        return value == null || value.isBlank();
    }

    public boolean hasReason() {
        return reason != null && !reason.isBlank();
    }
}
