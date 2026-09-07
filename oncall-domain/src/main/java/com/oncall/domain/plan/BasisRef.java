package com.oncall.domain.plan;

import java.util.Objects;

/**
 * 一条依据引用：「这一步是基于什么做出的」。
 *
 * <p>{@code ref} 是可追溯的标识（告警规则 ID、Runbook ID、日志行号等），
 * 不是自由文本描述——事后复盘要能顺着它找到原始出处。
 */
public record BasisRef(BasisType type, String ref) {

    public static final int MAX_REF_LENGTH = 191;

    public BasisRef {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(ref, "ref：依据必须可追溯，不得为 null");
        if (ref.isBlank()) {
            throw new IllegalArgumentException(
                    "ref 不得为空白——依据必须可追溯到具体出处，否则「有依据」等于没有");
        }
        // 绝不截断：截断后的 ID 会指向另一条规则或另一份 Runbook。
        if (ref.length() > MAX_REF_LENGTH) {
            throw new IllegalArgumentException("ref 超过列宽 " + MAX_REF_LENGTH
                    + "（实际 " + ref.length() + "）——绝不截断，截断会指向别的出处");
        }
    }

    public static BasisRef alertRule(String ruleId) {
        return new BasisRef(BasisType.ALERT_RULE, ruleId);
    }

    public static BasisRef runbook(String runbookId) {
        return new BasisRef(BasisType.RUNBOOK, runbookId);
    }

    public static BasisRef logText(String locator) {
        return new BasisRef(BasisType.LOG_TEXT, locator);
    }

    public boolean isTrusted() {
        return type.isTrusted();
    }
}
