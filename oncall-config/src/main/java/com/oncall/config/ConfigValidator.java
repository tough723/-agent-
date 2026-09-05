package com.oncall.config;

/**
 * 配置值校验器。
 *
 * <p>校验发生在**写入时**而不是读取时。理由：读的时候才发现值非法，
 * 系统已经带着坏配置跑了一段时间了；写的时候拒绝，坏值根本进不了库。
 *
 * <p>四层校验，按顺序：
 * <ol>
 *   <li>键必须在注册表声明过——杜绝拼写错误的键被静默写入；</li>
 *   <li>{@link ConfigTier#BACKEND_ONLY} 拒绝从前端通道写入；</li>
 *   <li>类型可解析；</li>
 *   <li>数值在 min/max 内、枚举在 allowedValues 内。</li>
 * </ol>
 */
public final class ConfigValidator {

    private final ConfigRegistry registry;

    public ConfigValidator(ConfigRegistry registry) {
        this.registry = registry;
    }

    /** 校验一次前端提交的改动。 */
    public ValidationResult validate(String key, String rawValue, boolean fromUi) {
        if (!registry.contains(key)) {
            return ValidationResult.rejected("未声明的配置键：" + key);
        }
        ConfigSpec spec = registry.require(key);

        if (fromUi && spec.tier() == ConfigTier.BACKEND_ONLY) {
            // 不把这类键的存在暴露给前端：连「这个键存在」都不该泄露
            return ValidationResult.rejected("配置键不存在或不可编辑：" + key);
        }
        if (rawValue == null || rawValue.isBlank()) {
            return ValidationResult.rejected("值不能为空（如需恢复默认请走 reset 接口）");
        }

        Object parsed = spec.type().parse(rawValue);
        if (parsed == null) {
            return ValidationResult.rejected(
                    "无法解析为 " + spec.type() + "：" + rawValue);
        }

        if (spec.type() == ConfigType.ENUM_STRING && !spec.allowedValues().contains(rawValue.trim())) {
            return ValidationResult.rejected("取值必须是 " + spec.allowedValues()
                    + " 之一，实际：" + rawValue);
        }

        if (parsed instanceof Number n && spec.hasBounds() && !spec.withinBounds(n.doubleValue())) {
            return ValidationResult.rejected("超出允许范围 " + spec.boundsText()
                    + "，实际：" + rawValue);
        }

        return ValidationResult.passed();
    }
}
