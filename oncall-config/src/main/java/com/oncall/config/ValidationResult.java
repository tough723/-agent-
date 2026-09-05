package com.oncall.config;

/**
 * 配置写入前的校验结果。
 *
 * <p>用返回值而不是异常：「用户填了个非法值」是正常业务分支，
 * 需要把原因原样回显给前端表单，不该走异常路径。
 *
 * @param valid   是否通过
 * @param message 未通过的原因；通过时为 {@code null}
 */
public record ValidationResult(boolean valid, String message) {

    public static ValidationResult passed() {
        return new ValidationResult(true, null);
    }

    public static ValidationResult rejected(String message) {
        return new ValidationResult(false, message);
    }
}
