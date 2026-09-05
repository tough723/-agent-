package com.oncall.config.admin;

import com.oncall.config.ConfigService.ConfigView;
import com.oncall.config.ConfigSpec;
import com.oncall.config.ConfigTier;
import com.oncall.config.ConfigType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("前端视图：敏感值必须掩码，且不能泄露长度")
class ConfigItemViewTest {

    private static ConfigSpec spec(boolean sensitive) {
        return ConfigSpec.builder("db.password", ConfigType.STRING, "default-secret", ConfigTier.RUNTIME_HOT)
                .group("测试")
                .description("测试用敏感项")
                .sensitive(sensitive)
                .build();
    }

    @Test
    @DisplayName("sensitive=true 时，生效值与默认值都不出现在响应里")
    void sensitiveValueIsMasked() {
        ConfigItemView view = ConfigItemView.of(
                new ConfigView(spec(true), "real-secret-value", true), false);

        assertThat(view.value()).isEqualTo(ConfigItemView.MASK);
        assertThat(view.defaultValue()).isEqualTo(ConfigItemView.MASK);
        assertThat(view.sensitive()).isTrue();
        // 掩码是定长的，不随真实值长度变化——长度也是信息
        assertThat(view.value()).hasSize(ConfigItemView.MASK.length());
    }

    @Test
    @DisplayName("sensitive=false 时正常回显，前端才能做「已改离默认」的高亮")
    void normalValueIsVisible() {
        ConfigItemView view = ConfigItemView.of(
                new ConfigView(spec(false), "5", true), false);
        assertThat(view.value()).isEqualTo("5");
        assertThat(view.defaultValue()).isEqualTo("default-secret");
        assertThat(view.overridden()).isTrue();
        assertThat(view.sensitive()).isFalse();
    }

    @Test
    @DisplayName("边界与候选值原样带给前端，让表单在提交前就能拦下非法输入")
    void boundsAndAllowedValuesAreCarried() {
        ConfigSpec s = ConfigSpec.builder("retrieval.top-n", ConfigType.INT, "5", ConfigTier.RUNTIME_HOT)
                .group("检索").description("x").bounds(1, 20).build();
        ConfigItemView view = ConfigItemView.of(new ConfigView(s, "5", false), false);
        assertThat(view.min()).isEqualTo(1.0);
        assertThat(view.max()).isEqualTo(20.0);

        ConfigSpec e = ConfigSpec.builder("autonomy.level", ConfigType.ENUM_STRING, "SHADOW", ConfigTier.RUNTIME_HOT)
                .group("放权").description("x").allowedValues(List.of("SHADOW", "SUGGEST")).build();
        assertThat(ConfigItemView.of(new ConfigView(e, "SHADOW", false), true).allowedValues())
                .containsExactly("SHADOW", "SUGGEST");
        assertThat(ConfigItemView.of(new ConfigView(e, "SHADOW", false), true).requiresApproval()).isTrue();
    }
}
