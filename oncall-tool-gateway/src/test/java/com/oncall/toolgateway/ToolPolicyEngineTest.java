package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolDeniedException;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.tool.ToolSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 策略引擎测试。
 *
 * <p>核心是<b>默认拒绝</b>——这是修复"MCP 工具绕过风险分级"的验收点：
 * MCP Server 运行期新增的工具不在白名单里，就必须调不通。
 */
class ToolPolicyEngineTest {

    private ToolPolicyEngine engine() {
        return new ToolPolicyEngine(List.of(
                ToolPolicy.readOnly("query_prometheus_alerts"),
                ToolPolicy.readOnly("query_logs"),
                ToolPolicy.highRisk("scale_replicas", Duration.ofMinutes(15))
        ));
    }

    @Test
    @DisplayName("白名单内的工具正常解析")
    void resolvesListedTools() {
        ToolPolicyEngine e = engine();
        assertThat(e.resolve("query_logs").risk()).isEqualTo(RiskLevel.READ_ONLY);
        assertThat(e.resolve("scale_replicas").requiresApproval()).isTrue();
        assertThat(e.size()).isEqualTo(3);
    }

    @Test
    @DisplayName("核心：未注册工具必须默认拒绝（模拟 MCP 运行期偷偷新增工具）")
    void unlistedToolIsDeniedByDefault() {
        ToolPolicyEngine e = engine();
        assertThatThrownBy(() -> e.resolve("mcp:jira:delete_issue"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("not in allowlist")
                .hasMessageContaining("mcp:jira:delete_issue");
    }

    @Test
    @DisplayName("拒绝事件必须被上报（工具投毒的告警信号）")
    void denialIsReported() {
        List<String> denied = new ArrayList<>();
        ToolPolicyEngine e = new ToolPolicyEngine(
                List.of(ToolPolicy.readOnly("query_logs")),
                (tool, reason) -> denied.add(tool + ":" + reason));

        assertThatThrownBy(() -> e.resolve("mcp:evil:rm_rf")).isInstanceOf(ToolDeniedException.class);
        assertThat(denied).containsExactly("mcp:evil:rm_rf:not in allowlist");
    }

    @Test
    @DisplayName("find 不抛异常，供 MCP 纳管时逐个过滤")
    void findIsNonThrowing() {
        ToolPolicyEngine e = engine();
        assertThat(e.find("query_logs")).isPresent();
        assertThat(e.find("mcp:unknown:x")).isEmpty();
    }

    @Test
    @DisplayName("策略可动态注册/吊销，不需要改代码重发布")
    void registerAndRevoke() {
        ToolPolicyEngine e = engine();
        assertThat(e.find("restart_pod")).isEmpty();

        e.register(new ToolPolicy("restart_pod", ToolSource.MCP, RiskLevel.HIGH,
                true, Duration.ofMinutes(10), true, null));
        assertThat(e.find("restart_pod")).isPresent();
        assertThat(e.resolve("restart_pod").requiresDualApproval()).isTrue();

        e.revoke("restart_pod");
        assertThat(e.find("restart_pod")).isEmpty();
    }

    @Test
    @DisplayName("写操作判定：只有 READ_ONLY 不算写")
    void writeOperationDetection() {
        assertThat(ToolPolicy.readOnly("x").isWriteOperation()).isFalse();
        assertThat(ToolPolicy.highRisk("y", Duration.ofMinutes(5)).isWriteOperation()).isTrue();
    }

    @Test
    @DisplayName("kill switch：READ_ONLY 模式下写工具被拒，只读工具放行")
    void killSwitchBlocksWrites() {
        KillSwitch ks = new KillSwitch();
        ks.set(RunMode.READ_ONLY);

        assertThatThrownBy(() -> ks.assertAllowed("scale_replicas", RiskLevel.HIGH))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("READ_ONLY");

        // 只读工具不受影响
        ks.assertAllowed("query_logs", RiskLevel.READ_ONLY);
    }

    @Test
    @DisplayName("kill switch：OFF 模式下一切工具被拒")
    void killSwitchOffBlocksEverything() {
        KillSwitch ks = new KillSwitch();
        ks.set(RunMode.OFF);
        assertThatThrownBy(() -> ks.assertAllowed("query_logs", RiskLevel.READ_ONLY))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("OFF");
    }

    @Test
    @DisplayName("kill switch：模式切换立即生效，无需重启")
    void killSwitchTakesEffectImmediately() {
        KillSwitch ks = new KillSwitch();
        assertThat(ks.mode()).isEqualTo(RunMode.FULL);
        ks.assertAllowed("scale_replicas", RiskLevel.HIGH);   // FULL 下不拦

        ks.set(RunMode.READ_ONLY);
        assertThatThrownBy(() -> ks.assertAllowed("scale_replicas", RiskLevel.HIGH))
                .isInstanceOf(ToolDeniedException.class);
    }

    @Test
    @DisplayName("幂等键：参数规范化后同语义不同字面量必须产生相同键")
    void idempotencyKeyIsStableUnderCanonicalization() {
        IdempotencyStore store = (runId, step, tool, args) ->
                runId + "|" + step + "|" + tool + "|" + GuardedToolCallback.canonical(args);

        String a = store.keyFor("run-1", 3, "scale_replicas", "{ \"replicas\": 5 }");
        String b = store.keyFor("run-1", 3, "scale_replicas", "{\"replicas\":5}");
        assertThat(a).isEqualTo(b);

        // 不同 run / 不同步 必须不同
        assertThat(store.keyFor("run-2", 3, "scale_replicas", "{\"replicas\":5}")).isNotEqualTo(b);
        assertThat(store.keyFor("run-1", 4, "scale_replicas", "{\"replicas\":5}")).isNotEqualTo(b);
    }
}
