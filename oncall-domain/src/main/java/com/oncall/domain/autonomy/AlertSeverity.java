package com.oncall.domain.autonomy;

/** 告警级别。决定自动化的允许范围。 */
public enum AlertSeverity {
    P0, P1, P2, P3;

    /** P0/P1 视为高危故障：任何写操作都不允许自动执行。 */
    public boolean isCritical() {
        return this == P0 || this == P1;
    }
}
