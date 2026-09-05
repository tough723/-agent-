package com.oncall.config;

/**
 * 一次配置变更的审计记录。
 *
 * <p>配置可以在运行期改，就意味着**「系统为什么突然 behaves differently」必须可追溯**。
 * 没有审计的运行期配置，等价于给系统装了一个没有日志的遥控器。
 *
 * <p>审计记录要落到与 {@code tool_audit_log} 同级的持久化存储，
 * 保留期不少于工具审计（建议 180 天）。
 *
 * @param key              配置键
 * @param oldValue         变更前的**生效值**（可能是默认值，不是"未设置"）
 * @param newValue         变更后的值；{@code null} 表示恢复默认
 * @param operator         操作人；系统自动调整为 {@code system}
 * @param reason           变更理由；**高风险配置项建议强制必填**
 * @param tier             该配置项的分级，便于按分级筛查改动
 * @param timestampMillis  变更时间
 */
public record ConfigChange(
        String key,
        String oldValue,
        String newValue,
        String operator,
        String reason,
        ConfigTier tier,
        long timestampMillis
) {

    /** 是否是"恢复默认"而不是"设置新值"。 */
    public boolean isReset() {
        return newValue == null;
    }

    /** 生效值是否真的变了（写入相同值不算变更，不产生审计噪音）。 */
    public boolean effectiveValueChanged() {
        if (newValue == null) {
            return oldValue != null;
        }
        return !newValue.equals(oldValue);
    }
}
