package com.oncall.toolgateway.mcp;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolDeniedException;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.tool.ToolSource;
import com.oncall.toolgateway.Approval;
import com.oncall.toolgateway.ApprovalGate;
import com.oncall.toolgateway.ArgClamper;
import com.oncall.toolgateway.GuardedToolCallback;
import com.oncall.toolgateway.InMemoryToolAuditLog;
import com.oncall.toolgateway.KillSwitch;
import com.oncall.toolgateway.RunMode;
import com.oncall.toolgateway.Sha256IdempotencyStore;
import com.oncall.toolgateway.ToolPolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MCP 显式纳管的测试（不变量 I14）。
 *
 * <p>这些用例覆盖的是<b>原始方案里那个 P0 漏洞</b>：
 * MCP 工具运行期才发现、打不了注解，于是绕过整套风险分级。
 * 其中最重要的一条是「策略查的是带前缀的名字」——
 * 前缀写错或改名没生效，白名单就永远匹配不上，
 * 而默认拒绝会让所有 MCP 工具静默不可用，故障现象是"工具不见了"而不是报错。
 */
class McpToolRegistrarTest {

    private static final String SERVER = "cmdb";
    private static final String RAW = "restart_service";
    private static final String NAMESPACED = "mcp:cmdb:restart_service";

    private StaticMcpToolCatalog catalog;
    private ToolPolicyEngine policyEngine;
    private KillSwitch killSwitch;
    private InMemoryToolAuditLog audit;
    private McpToolRegistrar registrar;

    @BeforeEach
    void setUp() {
        catalog = new StaticMcpToolCatalog();
        policyEngine = new ToolPolicyEngine(List.of());
        killSwitch = new KillSwitch();
        audit = new InMemoryToolAuditLog();
        registrar = new McpToolRegistrar(catalog, policyEngine, killSwitch,
                autoApprove(), audit, new Sha256IdempotencyStore(), ArgClamper.NOOP);
    }

    private static ToolPolicy mcpPolicy(String namespacedName, RiskLevel risk) {
        return new ToolPolicy(namespacedName, ToolSource.MCP, risk,
                false, Duration.ZERO, false, null);
    }

    private static ApprovalGate autoApprove() {
        return (key, policy, args) -> Approval.granted("auto");
    }

    // ------------------------------------------------------------ 纳管决策

    @Test
    @DisplayName("已纳管的工具被接受，且对外名字带 mcp:<server>: 前缀")
    void registeredToolIsAcceptedWithPrefix() {
        policyEngine.register(mcpPolicy(NAMESPACED, RiskLevel.READ_ONLY));
        catalog.withServer(SERVER, StubToolCallback.named(RAW));

        McpRegistrationResult result = registrar.inspect(SERVER);

        assertThat(result.isAvailable()).isTrue();
        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isZero();
        assertThat(result.accepted().get(0).namespacedName()).isEqualTo(NAMESPACED);
    }

    @Test
    @DisplayName("未纳管的工具被拒绝，且不静默——拒绝原因必须可见")
    void unregisteredToolIsRejectedAndReported() {
        catalog.withServer(SERVER, StubToolCallback.named(RAW));

        McpRegistrationResult result = registrar.inspect(SERVER);

        assertThat(result.acceptedCount()).isZero();
        assertThat(result.rejectedCount()).isEqualTo(1);
        assertThat(result.rejected().get(0).rawName()).isEqualTo(RAW);
        assertThat(result.rejected().get(0).reason()).isEqualTo("not in allowlist");
        // 静默丢弃的话，没人会知道 server 悄悄多提供了一个工具
        assertThat(audit.events()).anyMatch(e -> e.startsWith("DENIED:" + NAMESPACED));
    }

    @Test
    @DisplayName("只注册了原始名的策略不能给 MCP 工具背书 —— 前缀是安全边界不是命名习惯")
    void rawNamePolicyDoesNotAuthorizeMcpTool() {
        policyEngine.register(new ToolPolicy(RAW, ToolSource.MCP, RiskLevel.READ_ONLY,
                false, Duration.ZERO, false, null));
        catalog.withServer(SERVER, StubToolCallback.named(RAW));

        assertThat(registrar.inspect(SERVER).acceptedCount()).isZero();
    }

    @Test
    @DisplayName("LOCAL 策略不能给 MCP 工具背书 —— 否则本地注册同名策略就是绕过路径")
    void localPolicyDoesNotAuthorizeMcpTool() {
        policyEngine.register(new ToolPolicy(NAMESPACED, ToolSource.LOCAL, RiskLevel.READ_ONLY,
                false, Duration.ZERO, false, null));
        catalog.withServer(SERVER, StubToolCallback.named(RAW));

        McpRegistrationResult result = registrar.inspect(SERVER);

        assertThat(result.acceptedCount()).isZero();
        assertThat(result.rejected().get(0).reason()).contains("policy source mismatch");
    }

    @Test
    @DisplayName("两个 server 的同名工具是两个独立的东西，纳管一个不等于纳管另一个")
    void sameToolNameOnDifferentServersIsNotCrossAuthorized() {
        policyEngine.register(mcpPolicy(NAMESPACED, RiskLevel.READ_ONLY));
        catalog.withServer(SERVER, StubToolCallback.named(RAW))
                .withServer("billing", StubToolCallback.named(RAW));

        assertThat(registrar.inspect(SERVER).acceptedCount()).isEqualTo(1);
        // billing 的同名工具没有被纳管，不能因为 cmdb 的被纳管就放行
        assertThat(registrar.inspect("billing").acceptedCount()).isZero();
    }

    @Test
    @DisplayName("工具名含 ':' 被拒 —— 否则可以伪造 mcp:other-server:tool 冒用别人的纳管结果")
    void colonInToolNameIsRejected() {
        catalog.withServer(SERVER, StubToolCallback.named("billing:delete_all"));

        McpRegistrationResult result = registrar.inspect(SERVER);

        assertThat(result.acceptedCount()).isZero();
        assertThat(result.rejected().get(0).reason()).contains("伪造").contains(":");
    }

    @Test
    @DisplayName("工具名自带 mcp: 前缀被拒 —— 会造成双重前缀并绕过寻址")
    void selfPrefixedToolNameIsRejected() {
        catalog.withServer(SERVER, StubToolCallback.named("mcp:other:tool"));

        assertThat(registrar.inspect(SERVER).acceptedCount()).isZero();
    }

    @Test
    @DisplayName("工具名含空白被拒 —— 日志与提示词里会被切开，审计对不上")
    void whitespaceInToolNameIsRejected() {
        catalog.withServer(SERVER, StubToolCallback.named("restart service"));

        McpRegistrationResult result = registrar.inspect(SERVER);

        assertThat(result.acceptedCount()).isZero();
        assertThat(result.rejected().get(0).reason()).contains("非法字符");
    }

    @Test
    @DisplayName("同一 server 内重名，只有第一个被接受")
    void duplicateToolNameWithinServerIsRejected() {
        policyEngine.register(mcpPolicy(NAMESPACED, RiskLevel.READ_ONLY));
        catalog.withServer(SERVER, StubToolCallback.named(RAW), StubToolCallback.named(RAW));

        McpRegistrationResult result = registrar.inspect(SERVER);

        assertThat(result.acceptedCount()).isEqualTo(1);
        assertThat(result.rejectedCount()).isEqualTo(1);
        assertThat(result.rejected().get(0).reason()).contains("重复");
    }

    @Test
    @DisplayName("server 名非法直接抛异常 —— 那是配置错误，不是运行期发现结果")
    void illegalServerNameThrows() {
        assertThatThrownBy(() -> registrar.inspect("bad:server"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registrar.inspect("bad server"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registrar.inspect(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("server 不可达不抛异常 —— 一个 MCP server 挂掉不该让整个 Agent 起不来")
    void unreachableServerDegradesInsteadOfThrowing() {
        catalog.withFailure(SERVER, new IllegalStateException("connect timeout"));

        McpRegistrationResult result = registrar.inspect(SERVER);

        assertThat(result.isAvailable()).isFalse();
        assertThat(result.catalogError()).contains("IllegalStateException");
        assertThat(result.acceptedCount()).isZero();
        // 但必须留下痕迹，否则运维只会看到"工具变少了"
        assertThat(audit.countOf("DENIED:")).isEqualTo(1);
    }

    @Test
    @DisplayName("空目录返回空结果而不是 null")
    void emptyCatalogYieldsEmptyResult() {
        McpRegistrationResult result = registrar.inspect(SERVER);

        assertThat(result.isAvailable()).isTrue();
        assertThat(result.accepted()).isEmpty();
        assertThat(result.rejected()).isEmpty();
    }

    // ------------------------------------------------------------ 守卫是否真的生效

    @Test
    @DisplayName("模型看到的名字必须是带前缀的名字，原始名不得泄露")
    void rawNameNeverLeaksToTheModel() {
        policyEngine.register(mcpPolicy(NAMESPACED, RiskLevel.READ_ONLY));
        catalog.withServer(SERVER, StubToolCallback.named(RAW));

        List<ToolCallback> tools = registrar.guardedTools(SERVER, "run-1", 1);

        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).getToolDefinition().name()).isEqualTo(NAMESPACED);
        assertThat(tools.get(0).getToolDefinition().name()).doesNotContain("mcp:cmdb:mcp:");
    }

    @Test
    @DisplayName("改名只改名字：description 与 inputSchema 必须原样保留")
    void renamingPreservesDescriptionAndSchema() {
        policyEngine.register(mcpPolicy(NAMESPACED, RiskLevel.READ_ONLY));
        catalog.withServer(SERVER, StubToolCallback.named(RAW));

        ToolCallback guarded = registrar.guardedTools(SERVER, "run-1", 1).get(0);

        assertThat(guarded.getToolDefinition().description()).contains(RAW);
        assertThat(guarded.getToolDefinition().inputSchema()).isEqualTo("{\"type\":\"object\"}");
    }

    @Test
    @DisplayName("策略判定用的是带前缀的名字 —— 这条失败说明改名没生效")
    void policyIsResolvedByNamespacedName() {
        policyEngine.register(mcpPolicy(NAMESPACED, RiskLevel.READ_ONLY));
        StubToolCallback stub = StubToolCallback.named(RAW);
        catalog.withServer(SERVER, stub);

        ToolCallback guarded = registrar.guardedTools(SERVER, "run-1", 1).get(0);
        String result = guarded.call("{}");

        // 如果查的是原始名 restart_service，白名单里没有它，默认拒绝会抛异常
        assertThat(result).isEqualTo("ok:" + RAW);
        assertThat(stub.calls).isEqualTo(1);
        assertThat(audit.events()).contains("SUCCESS:" + NAMESPACED);
    }

    @Test
    @DisplayName("撤掉带前缀的策略后立刻拒绝 —— 纳管是唯一授权来源")
    void revokingPolicyImmediatelyDenies() {
        policyEngine.register(mcpPolicy(NAMESPACED, RiskLevel.READ_ONLY));
        catalog.withServer(SERVER, StubToolCallback.named(RAW));
        ToolCallback guarded = registrar.guardedTools(SERVER, "run-1", 1).get(0);

        policyEngine.revoke(NAMESPACED);

        assertThatThrownBy(() -> guarded.call("{}"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining(NAMESPACED);
    }

    @Test
    @DisplayName("kill switch 对 MCP 工具同样生效 —— 纳管不是豁免")
    void killSwitchAppliesToMcpTools() {
        policyEngine.register(mcpPolicy("mcp:cmdb:scale", RiskLevel.LOW));
        catalog.withServer(SERVER, StubToolCallback.named("scale"));
        ToolCallback guarded = registrar.guardedTools(SERVER, "run-1", 1).get(0);

        killSwitch.set(RunMode.READ_ONLY);

        assertThatThrownBy(() -> guarded.call("{}")).isInstanceOf(ToolDeniedException.class);
    }

    @Test
    @DisplayName("产出的回调必须是 GuardedToolCallback —— 与 ArchUnit F9 是同一件事的运行期版本")
    void guardedToolsAreActuallyGuarded() {
        policyEngine.register(mcpPolicy(NAMESPACED, RiskLevel.READ_ONLY));
        catalog.withServer(SERVER, StubToolCallback.named(RAW));

        for (ToolCallback t : registrar.guardedTools(SERVER, "run-1", 1)) {
            assertThat(t).isInstanceOf(GuardedToolCallback.class);
            assertThat(((GuardedToolCallback) t).toolName()).isEqualTo(NAMESPACED);
        }
    }

    @Test
    @DisplayName("未纳管的工具不会出现在 guardedTools 里，也永远不会被调用")
    void unregisteredToolIsNeverExecutable() {
        catalog.withServer(SERVER, StubToolCallback.named("rogue"));

        assertThat(registrar.guardedTools(SERVER, "run-1", 1)).isEmpty();
    }

    @Test
    @DisplayName("runId 与 step 进幂等键：同一工具在不同步序号下是两次执行")
    void runAndStepArePartOfIdempotency() {
        policyEngine.register(mcpPolicy(NAMESPACED, RiskLevel.READ_ONLY));
        StubToolCallback stub = StubToolCallback.named(RAW);
        catalog.withServer(SERVER, stub);

        registrar.guardedTools(SERVER, "run-1", 1).get(0).call("{}");
        registrar.guardedTools(SERVER, "run-1", 2).get(0).call("{}");
        // 同一 run 同一 step 的重放不应再次真正执行
        registrar.guardedTools(SERVER, "run-1", 2).get(0).call("{}");

        assertThat(stub.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("协作者为 null 立即失败，而不是等到第一次纳管")
    void nullCollaboratorRejected() {
        assertThatThrownBy(() -> new McpToolRegistrar(null, policyEngine, killSwitch,
                autoApprove(), audit, new Sha256IdempotencyStore(), ArgClamper.NOOP))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("名字拼装是可预测的：mcp:<server>:<tool>")
    void nameFormatIsStable() {
        assertThat(McpToolRegistrar.namespacedName("cmdb", "restart")).isEqualTo("mcp:cmdb:restart");
    }
}
