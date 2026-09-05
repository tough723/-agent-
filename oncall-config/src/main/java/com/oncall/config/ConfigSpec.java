package com.oncall.config;

import java.util.Collections;
import java.util.List;

/**
 * 一个配置项的完整声明：类型、默认值、可见性分级、合法边界、以及给人看的说明。
 *
 * <p>这份声明同时服务三个消费方，所以必须集中在一处：
 * <ul>
 *   <li><b>前端</b>——据此渲染表单控件、显示取值范围与说明（见 schema 导出）；</li>
 *   <li><b>后端校验</b>——写入前按 type + min/max + allowedValues 拒绝非法值；</li>
 *   <li><b>评审与审计</b>——「哪些参数被冻结在什么值」直接读这份声明即可，
 *       不必翻代码找散落的常量。</li>
 * </ul>
 *
 * <p><b>边界不是可选项。</b>凡是 {@link ConfigTier#RUNTIME_HOT} 的数值型参数都必须给
 * min/max，否则前端就是一个能把系统调崩的输入框。例如 embedding 单批文档数的上界
 * 必须是 10——那是上游 API 的硬限制，把它编码成边界，前端就物理上填不出 10000。
 *
 * @param key            配置键，点分命名，如 {@code retrieval.candidate-size}
 * @param type           值类型
 * @param defaultValue   默认值字面量；**这就是被冻结并签字的基线值**
 * @param tier           可见性与可变性分级
 * @param group          分组，前端按此分栏展示
 * @param description    给人看的说明，必须写清「改了会影响什么」
 * @param min            数值下界；非数值型或不限时为 {@code null}
 * @param max            数值上界；同上
 * @param allowedValues  {@link ConfigType#ENUM_STRING} 的合法取值；其余类型为空列表
 * @param migrationHint  {@link ConfigTier#REQUIRES_MIGRATION} 时必须说明要跑什么流程
 * @param sensitive      是否敏感（前端展示为掩码）
 */
public record ConfigSpec(
        String key,
        ConfigType type,
        String defaultValue,
        ConfigTier tier,
        String group,
        String description,
        Double min,
        Double max,
        List<String> allowedValues,
        String migrationHint,
        boolean sensitive
) {

    public ConfigSpec {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("key 不能为空");
        }
        if (type == null) {
            throw new IllegalArgumentException("type 不能为空：" + key);
        }
        if (tier == null) {
            throw new IllegalArgumentException("tier 不能为空：" + key);
        }
        if (defaultValue == null) {
            throw new IllegalArgumentException("defaultValue 不能为空：" + key);
        }
        allowedValues = allowedValues == null ? List.of() : List.copyOf(allowedValues);
    }

    public static Builder builder(String key, ConfigType type, String defaultValue, ConfigTier tier) {
        return new Builder(key, type, defaultValue, tier);
    }

    public boolean hasBounds() {
        return min != null || max != null;
    }

    /** 数值是否落在声明的边界内（含端点）。无边界时恒为 true。 */
    public boolean withinBounds(double value) {
        if (min != null && value < min) {
            return false;
        }
        return max == null || !(value > max);
    }

    /** 前端是否展示。BACKEND_ONLY 一律不展示。 */
    public boolean visibleInUi() {
        return tier.visibleInUi();
    }

    /** REQUIRES_MIGRATION 却没写迁移说明，属于声明缺陷——启动时就该被自检抓出来。 */
    public boolean declarationIsComplete() {
        if (tier == ConfigTier.REQUIRES_MIGRATION
                && (migrationHint == null || migrationHint.isBlank())) {
            return false;
        }
        if (type == ConfigType.ENUM_STRING && allowedValues.isEmpty()) {
            return false;
        }
        return true;
    }

    /** 供前端渲染用的取值范围文案。 */
    public String boundsText() {
        if (!hasBounds()) {
            return "";
        }
        String lo = min == null ? "-∞" : trimNumber(min);
        String hi = max == null ? "+∞" : trimNumber(max);
        return "[" + lo + ", " + hi + "]";
    }

    private static String trimNumber(double d) {
        if (d == Math.rint(d) && !Double.isInfinite(d)) {
            return String.valueOf((long) d);
        }
        return String.valueOf(d);
    }

    /** 声明式构建器。刻意不做 setter 链之外的魔法，便于 review。 */
    public static final class Builder {
        private final String key;
        private final ConfigType type;
        private final String defaultValue;
        private final ConfigTier tier;
        private String group = "general";
        private String description = "";
        private Double min;
        private Double max;
        private List<String> allowedValues = Collections.emptyList();
        private String migrationHint;
        private boolean sensitive;

        private Builder(String key, ConfigType type, String defaultValue, ConfigTier tier) {
            this.key = key;
            this.type = type;
            this.defaultValue = defaultValue;
            this.tier = tier;
        }

        public Builder group(String v) { this.group = v; return this; }
        public Builder description(String v) { this.description = v; return this; }
        public Builder bounds(double lo, double hi) { this.min = lo; this.max = hi; return this; }
        public Builder min(double lo) { this.min = lo; return this; }
        public Builder max(double hi) { this.max = hi; return this; }
        public Builder allowedValues(List<String> v) { this.allowedValues = v; return this; }
        public Builder migrationHint(String v) { this.migrationHint = v; return this; }
        public Builder sensitive(boolean v) { this.sensitive = v; return this; }

        public ConfigSpec build() {
            return new ConfigSpec(key, type, defaultValue, tier, group, description,
                    min, max, allowedValues, migrationHint, sensitive);
        }
    }
}
