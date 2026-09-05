package com.oncall.toolgateway.governance;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.tool.ToolSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 风险方向判定的验收测试。
 *
 * <p>这个类是工具策略治理的<b>判据</b>：它决定一次变更要不要凑齐两个人。
 * 判错了两个方向都出事——把放宽判成收紧，等于双人复核形同虚设；
 * 把收紧判成放宽，等于把"撤掉一个危险工具"卡在流程里，
 * 结果那个工具继续留在白名单上。
 */
class PolicyRiskDeltaTest {

    private static ToolPolicy policy(RiskLevel risk, boolean approval, boolean dual,
                                     Duration timeout, String schema) {
        return new ToolPolicy("scale_replicas", ToolSource.LOCAL, risk,
                approval, timeout, dual, schema);
    }

    private static ToolPolicy baseline() {
        return policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), null);
    }

    // ------------------------------------------------------------------ 新增与撤销

    @Test
    @DisplayName("新增任何工具都算放宽——包括 READ_ONLY")
    void grantAlwaysWidens() {
        // 刻意不给只读工具开例外：加进白名单就等于允许模型调用它，
        // 而"只读"这个标签本身正是发起人的断言——复核人要做的事就是核对它。
        // 数据泄露不需要写权限。
        for (RiskLevel r : RiskLevel.values()) {
            ToolPolicy p = policy(r, false, false, Duration.ZERO, null);
            assertThat(PolicyRiskDelta.between(null, p).widens())
                    .as("新增 %s 工具", r).isTrue();
        }
    }

    @Test
    @DisplayName("撤销不算放宽——默认拒绝之下，移除一条策略让系统严格变安全")
    void revokeNeverWidens() {
        PolicyRiskDelta d = PolicyRiskDelta.between(baseline(), null);

        assertThat(d.widens()).isFalse();
        assertThat(d.reasons()).anyMatch(s -> s.contains("撤销"));
    }

    @Test
    @DisplayName("撤销一条并不存在的策略：无害空操作，不算放宽")
    void revokeOfMissingPolicyIsHarmless() {
        assertThat(PolicyRiskDelta.between(null, null).widens()).isFalse();
    }

    // ------------------------------------------------------------------ 逐维度

    @Test
    @DisplayName("风险等级下调 = 放宽")
    void loweringRiskWidens() {
        assertThat(PolicyRiskDelta.between(
                policy(RiskLevel.HIGH, true, false, Duration.ofMinutes(15), null),
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), null)).widens()).isTrue();
        assertThat(PolicyRiskDelta.between(
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), null),
                policy(RiskLevel.READ_ONLY, true, false, Duration.ofMinutes(15), null)).widens()).isTrue();
    }

    @Test
    @DisplayName("风险等级上调 = 收紧，不需要双人")
    void raisingRiskDoesNotWiden() {
        assertThat(PolicyRiskDelta.between(
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), null),
                policy(RiskLevel.HIGH, true, false, Duration.ofMinutes(15), null)).widens()).isFalse();
    }

    @Test
    @DisplayName("取消人工审批 = 放宽")
    void droppingApprovalWidens() {
        assertThat(PolicyRiskDelta.between(
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), null),
                policy(RiskLevel.LOW, false, false, Duration.ofMinutes(15), null)).widens()).isTrue();
    }

    @Test
    @DisplayName("新增人工审批 = 收紧")
    void addingApprovalDoesNotWiden() {
        assertThat(PolicyRiskDelta.between(
                policy(RiskLevel.LOW, false, false, Duration.ofMinutes(15), null),
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), null)).widens()).isFalse();
    }

    @Test
    @DisplayName("取消双人复核 = 放宽")
    void droppingDualApprovalWidens() {
        assertThat(PolicyRiskDelta.between(
                policy(RiskLevel.HIGH, true, true, Duration.ofMinutes(15), null),
                policy(RiskLevel.HIGH, true, false, Duration.ofMinutes(15), null)).widens()).isTrue();
    }

    @Test
    @DisplayName("去掉参数级 Schema 校验 = 放宽")
    void droppingArgsSchemaWidens() {
        assertThat(PolicyRiskDelta.between(
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), "{\"type\":\"object\"}"),
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), null)).widens()).isTrue();
    }

    @Test
    @DisplayName("新增参数级 Schema 校验 = 收紧")
    void addingArgsSchemaDoesNotWiden() {
        assertThat(PolicyRiskDelta.between(
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), null),
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), "{\"type\":\"object\"}"))
                .widens()).isFalse();
    }

    @Test
    @DisplayName("审批超时延长 = 放宽（超时是到点自动拒绝，等得越久越可能等到人批下来）")
    void longerApprovalTimeoutWidens() {
        assertThat(PolicyRiskDelta.between(
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(5), null),
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(30), null)).widens()).isTrue();
        assertThat(PolicyRiskDelta.between(
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(30), null),
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(5), null)).widens()).isFalse();
    }

    @Test
    @DisplayName("来源变化不算宽窄——换的是信任边界，不是权限大小；但必须记进理由")
    void sourceChangeIsNotAWideningButIsRecorded() {
        ToolPolicy before = new ToolPolicy("scale_replicas", ToolSource.LOCAL, RiskLevel.LOW,
                true, Duration.ofMinutes(15), false, null);
        ToolPolicy after = new ToolPolicy("scale_replicas", ToolSource.MCP, RiskLevel.LOW,
                true, Duration.ofMinutes(15), false, null);

        PolicyRiskDelta d = PolicyRiskDelta.between(before, after);

        assertThat(d.widens()).as("单纯换来源不该触发双人复核").isFalse();
        assertThat(d.reasons()).anyMatch(s -> s.contains("信任边界"));
    }

    @Test
    @DisplayName("完全没变 = 不算放宽")
    void noChangeDoesNotWiden() {
        assertThat(PolicyRiskDelta.between(baseline(), baseline()).widens()).isFalse();
    }

    // ------------------------------------------------------------------ 组合规则

    @Test
    @DisplayName("一处收紧抵不了一处放宽——刻意用「任一」而不是打分")
    void tighteningOneDimensionDoesNotOffsetWideningAnother() {
        // 风险从 HIGH 提到……不，这里反过来：审批加上了（收紧），
        // 但风险等级降了（放宽）。打分制会互相抵消，"任一"不会。
        PolicyRiskDelta d = PolicyRiskDelta.between(
                policy(RiskLevel.HIGH, false, false, Duration.ofMinutes(15), null),
                policy(RiskLevel.LOW, true, false, Duration.ofMinutes(15), null));

        assertThat(d.widens()).isTrue();
        assertThat(d.reasons()).anyMatch(s -> s.contains("风险等级下调"));
    }

    @Test
    @DisplayName("不能在一次变更里改工具名——改名等于撤销 + 新增，必须拆成两次")
    void renamingInOneChangeIsRejected() {
        ToolPolicy a = new ToolPolicy("scale_replicas", ToolSource.LOCAL, RiskLevel.LOW,
                true, Duration.ofMinutes(15), false, null);
        ToolPolicy b = new ToolPolicy("restart_pod", ToolSource.LOCAL, RiskLevel.LOW,
                true, Duration.ofMinutes(15), false, null);

        assertThatThrownBy(() -> PolicyRiskDelta.between(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("改名等于撤销 + 新增");
    }

    @Test
    @DisplayName("理由列表不可变——它会被界面与审计持有")
    void reasonsAreImmutable() {
        PolicyRiskDelta d = PolicyRiskDelta.between(null, baseline());

        assertThatThrownBy(() -> d.reasons().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null 理由列表被规整为空列表，不是 NPE")
    void nullReasonsBecomeEmptyList() {
        assertThat(new PolicyRiskDelta(false, null).reasons()).isEmpty();
    }
}
