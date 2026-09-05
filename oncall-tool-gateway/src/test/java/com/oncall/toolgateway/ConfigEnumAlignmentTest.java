package com.oncall.toolgateway;

import com.oncall.config.ConfigRegistry;
import com.oncall.config.ConfigSpec;
import com.oncall.config.OnCallConfigKeys;
import com.oncall.config.OnCallConfigRegistry;
import com.oncall.domain.autonomy.AutonomyLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置声明与真实枚举的一致性测试。
 *
 * <p>{@code oncall-config} 刻意保持零外部依赖，所以放权等级与运行模式在配置里
 * 只能以字符串集合（{@code allowedValues}）声明。字符串和枚举之间没有编译期约束——
 * 一旦有人给枚举加了新常量却忘了改配置声明，前端下拉框就会少一个选项，
 * 或者反过来前端给出后端不认的值。这类错位必须在 CI 里被抓到。
 *
 * <p>这个测试放在 tool-gateway 是因为它同时能看见三方：
 * config 的声明、gateway 的 {@link RunMode}、domain 的 {@link AutonomyLevel}。
 */
class ConfigEnumAlignmentTest {

    private final ConfigRegistry registry = OnCallConfigRegistry.create();

    @Test
    @DisplayName("autonomy.level 的取值与 AutonomyLevel 枚举逐一对应")
    void autonomyLevelMatchesEnum() {
        List<String> declared = registry.require(OnCallConfigKeys.AUTONOMY_LEVEL).allowedValues();
        List<String> actual = Arrays.stream(AutonomyLevel.values()).map(Enum::name).toList();
        assertThat(declared).containsExactlyElementsOf(actual);
    }

    @Test
    @DisplayName("autonomy.kill-switch-mode 的取值与 RunMode 枚举逐一对应")
    void killSwitchModeMatchesRunMode() {
        List<String> declared = registry.require(OnCallConfigKeys.AUTONOMY_KILL_SWITCH_MODE).allowedValues();
        List<String> actual = Arrays.stream(RunMode.values()).map(Enum::name).toList();
        assertThat(declared).containsExactlyElementsOf(actual);
    }

    @Test
    @DisplayName("放权等级默认值必须是枚举里真实存在的常量，且是最低放权档")
    void autonomyDefaultIsRealAndMostConservative() {
        ConfigSpec spec = registry.require(OnCallConfigKeys.AUTONOMY_LEVEL);
        AutonomyLevel def = AutonomyLevel.valueOf(spec.defaultValue());
        assertThat(def).isEqualTo(AutonomyLevel.SHADOW);
    }

    @Test
    @DisplayName("kill switch 默认值必须是枚举里真实存在的常量")
    void killSwitchDefaultIsReal() {
        ConfigSpec spec = registry.require(OnCallConfigKeys.AUTONOMY_KILL_SWITCH_MODE);
        RunMode def = RunMode.valueOf(spec.defaultValue());
        assertThat(def).isEqualTo(RunMode.FULL);
    }
}
