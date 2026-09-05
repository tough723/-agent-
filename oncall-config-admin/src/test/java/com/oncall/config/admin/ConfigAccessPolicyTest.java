package com.oncall.config.admin;

import com.oncall.config.ConfigRegistry;
import com.oncall.config.ConfigSpec;
import com.oncall.config.ConfigTier;
import com.oncall.config.OnCallConfigKeys;
import com.oncall.config.OnCallConfigRegistry;
import com.oncall.domain.governance.Operator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("配置权限策略：判定依据是「这个改动会造成什么后果」，不是「这个人是谁」")
class ConfigAccessPolicyTest {

    private final ConfigAccessPolicy policy = new ConfigAccessPolicy();
    private final ConfigRegistry registry = OnCallConfigRegistry.create();

    private ConfigSpec spec(String key) {
        return registry.require(key);
    }

    private static Operator op(String name, Operator.Role role) {
        return new Operator(name, role);
    }

    @Test
    @DisplayName("BACKEND_ONLY 项一律不可见：兜底参数与凭据的存在性本身就是信息")
    void backendOnlyIsInvisible() {
        assertThat(spec(OnCallConfigKeys.VECTOR_DIMENSION).tier())
                .isEqualTo(ConfigTier.BACKEND_ONLY);
        assertThat(policy.isVisible(spec(OnCallConfigKeys.VECTOR_DIMENSION))).isFalse();
        // 即使是 ADMIN 也不能写——不是权限不够，是根本不该出现在这个界面上
        assertThat(policy.canWriteDirectly(op("root", Operator.Role.ADMIN),
                spec(OnCallConfigKeys.VECTOR_DIMENSION))).isFalse();
        assertThat(policy.canPropose(op("root", Operator.Role.ADMIN),
                spec(OnCallConfigKeys.VECTOR_DIMENSION))).isFalse();
    }

    @Test
    @DisplayName("普通项：EDITOR 可以直接改")
    void editorCanWriteNormalKey() {
        assertThat(policy.canWriteDirectly(op("alice", Operator.Role.EDITOR),
                spec(OnCallConfigKeys.RETRIEVAL_TOP_N))).isTrue();
    }

    @Test
    @DisplayName("VIEWER 什么都不能改")
    void viewerCannotWrite() {
        assertThat(policy.canWriteDirectly(op("bob", Operator.Role.VIEWER),
                spec(OnCallConfigKeys.RETRIEVAL_TOP_N))).isFalse();
        assertThat(policy.canApprove(op("bob", Operator.Role.VIEWER))).isFalse();
    }

    @Test
    @DisplayName("高危项：ADMIN 也不能单独改——ADMIN 拥有的是复核权，不是单独决定权")
    void adminCannotWriteHighRiskAlone() {
        assertThat(policy.canWriteDirectly(op("root", Operator.Role.ADMIN),
                spec(OnCallConfigKeys.AUTONOMY_LEVEL))).isFalse();
        // 但可以发起，也可以复核（复核时受"不能复核自己"约束，见控制器测试）
        assertThat(policy.canPropose(op("root", Operator.Role.ADMIN),
                spec(OnCallConfigKeys.AUTONOMY_LEVEL))).isTrue();
        assertThat(policy.canApprove(op("root", Operator.Role.ADMIN))).isTrue();
    }

    @Test
    @DisplayName("高危清单就是那 5 个键，一个不多一个不少")
    void highRiskKeyList() {
        assertThat(ConfigAccessPolicy.HIGH_RISK_KEYS).containsExactlyInAnyOrder(
                OnCallConfigKeys.AUTONOMY_LEVEL,
                OnCallConfigKeys.AUTONOMY_KILL_SWITCH_MODE,
                OnCallConfigKeys.RETRIEVAL_RERANK_ENABLED,
                OnCallConfigKeys.AGENT_MAX_STEPS,
                OnCallConfigKeys.ALERT_STORM_THRESHOLD_PER_MINUTE);
        for (String key : ConfigAccessPolicy.HIGH_RISK_KEYS) {
            assertThat(policy.requiresSecondApproval(key)).as(key).isTrue();
            // 高危键必须真的在声明表里，否则清单会静默失效
            assertThat(registry.contains(key)).as(key + " 必须已声明").isTrue();
        }
    }

    @Test
    @DisplayName("未声明的键不算高危——它会先在 404 分支被挡掉")
    void unknownKeyIsNotHighRisk() {
        assertThat(policy.requiresSecondApproval("no.such.key")).isFalse();
    }

    @Test
    @DisplayName("身份解析：无法识别的角色按最低权限处理，不给默认 EDITOR")
    void unknownRoleFallsBackToViewer() {
        assertThat(Operator.fromHeaders("alice", "SUPERUSER").role())
                .isEqualTo(Operator.Role.VIEWER);
        assertThat(Operator.fromHeaders("alice", null).role())
                .isEqualTo(Operator.Role.VIEWER);
        assertThat(Operator.fromHeaders("alice", "editor").role())
                .isEqualTo(Operator.Role.EDITOR);
    }

    @Test
    @DisplayName("缺少身份头 → 匿名，由控制器决定 401")
    void missingPrincipalIsAnonymous() {
        assertThat(Operator.fromHeaders(null, "ADMIN").isAnonymous()).isTrue();
        assertThat(Operator.fromHeaders("   ", "ADMIN").isAnonymous()).isTrue();
        assertThat(Operator.fromHeaders("alice", "ADMIN").isAnonymous()).isFalse();
    }
}
