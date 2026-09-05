package com.oncall.config.admin;

import com.oncall.config.ConfigSpec;
import com.oncall.config.ConfigType;
import com.oncall.config.ConfigService.ConfigView;

import java.util.List;

/**
 * 单个配置项在前端的呈现。
 *
 * <p>不直接把 {@link ConfigSpec} 序列化出去，有两个原因：
 * <ol>
 *   <li><b>敏感值必须掩码</b>。{@code ConfigSpec} 有 {@code sensitive} 标记，
 *       被标记的项（凭据类）不能把生效值回显到前端——即使它已经
 *       属于 BACKEND_ONLY 不会出现在列表里，也不能依赖这一层作为唯一防线。</li>
 *   <li><b>要补 {@code requiresApproval}</b>。前端需要在表单上提前告知
 *       "这一项保存后还要别人复核"，而不是让用户点了保存才发现要等。</li>
 * </ol>
 *
 * @param value 当前生效值；敏感项为掩码串
 */
public record ConfigItemView(
        String key,
        ConfigType type,
        String group,
        String description,
        String value,
        String defaultValue,
        boolean overridden,
        Double min,
        Double max,
        List<String> allowedValues,
        String migrationHint,
        boolean sensitive,
        boolean requiresApproval
) {

    /** 敏感值的掩码串。刻意不暴露长度——长度也是信息。 */
    public static final String MASK = "••••••";

    public static ConfigItemView of(ConfigView view, boolean requiresApproval) {
        ConfigSpec s = view.spec();
        boolean sensitive = s.sensitive();
        return new ConfigItemView(
                s.key(),
                s.type(),
                s.group(),
                s.description(),
                sensitive ? MASK : view.effectiveValue(),
                sensitive ? MASK : s.defaultValue(),
                view.overridden(),
                s.min(),
                s.max(),
                s.allowedValues(),
                s.migrationHint(),
                sensitive,
                requiresApproval
        );
    }
}
