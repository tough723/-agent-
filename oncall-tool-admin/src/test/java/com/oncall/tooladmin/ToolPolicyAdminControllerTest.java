package com.oncall.tooladmin;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.tool.ToolSource;
import com.oncall.toolgateway.ToolPolicyEngine;
import com.oncall.toolgateway.ToolPolicyGovernance;
import com.oncall.toolgateway.governance.InMemoryToolPolicyChangeAudit;
import com.oncall.toolgateway.governance.InMemoryToolPolicyChangeTicketStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 工具白名单治理接口的行为测试。
 *
 * <p>用 {@code standaloneSetup} 而不是 {@code @SpringBootTest}：不起容器，
 * 只验证路由、参数绑定、状态码与异常翻译。
 *
 * <p><b>断言刻意避开中文文案</b>：{@code MockHttpServletResponse} 在未显式声明
 * charset 时按 ISO-8859-1 解码，直接比对中文串会因为编码而不是因为逻辑失败。
 * 所以只对状态码与 ASCII 字段（{@code code} / {@code applied} / {@code widens}）
 * 做精确断言。
 *
 * <p><b>这一层测的是映射，不是判定</b>。判定（要不要两个人、能不能自审、
 * 过期了怎么办）在 {@code ToolPolicyGovernanceTest} 与 {@code TwoPersonReviewTest} 里。
 * 这里只验证：四种拒绝是否落到了各自的状态码与机器码上——
 * 因为"换人"和"重新发起"是两件不同的事，前端要靠它们分支。
 *
 * <p>顺带说明：这个测试在 {@code com.oncall.tooladmin} 包里，
 * 因此<b>没法</b>调 {@code ToolPolicyEngine.register()}（包级可见）——
 * 策略只能从构造器灌进去。这正是 A4 那一步要的效果。
 */
@DisplayName("工具白名单治理 REST 接口")
class ToolPolicyAdminControllerTest {

    private static final String MCP_TOOL = "mcp:cmdb:scale";
    private static final String LOCAL_TOOL = "scale_replicas";

    private final AtomicLong now = new AtomicLong(1_000_000L);

    private ToolPolicyEngine engine;
    private InMemoryToolPolicyChangeTicketStore store;
    private InMemoryToolPolicyChangeAudit audit;
    private ToolPolicyGovernance governance;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        givenPolicies();
    }

    private void givenPolicies(ToolPolicy... policies) {
        engine = new ToolPolicyEngine(List.of(policies));
        store = new InMemoryToolPolicyChangeTicketStore();
        audit = new InMemoryToolPolicyChangeAudit();
        governance = new ToolPolicyGovernance(engine, store, audit, now::get);
        mvc = MockMvcBuilders
                .standaloneSetup(new ToolPolicyAdminController(engine, governance, audit))
                .setControllerAdvice(new ToolAdminExceptionHandler())
                .build();
    }

    private static ToolPolicy mcpHigh() {
        return new ToolPolicy(MCP_TOOL, ToolSource.MCP, RiskLevel.HIGH,
                true, Duration.ofMinutes(15), false, null);
    }

    private static ToolPolicy mcpLow() {
        return new ToolPolicy(MCP_TOOL, ToolSource.MCP, RiskLevel.LOW,
                true, Duration.ofMinutes(15), false, null);
    }

    /** 一个放宽方向的请求体（新增一个 MCP 高危工具）。 */
    private static String grantBody(String toolName, String reason) {
        return "{\"kind\":\"GRANT\",\"toolName\":\"" + toolName
                + "\",\"source\":\"MCP\",\"risk\":\"HIGH\",\"requiresApproval\":true,"
                + "\"approvalTimeoutSeconds\":900,\"reason\":\"" + reason + "\"}";
    }

    private static String revokeBody(String toolName, String reason) {
        return "{\"kind\":\"REVOKE\",\"toolName\":\"" + toolName
                + "\",\"reason\":\"" + reason + "\"}";
    }

    // ------------------------------------------------------------------ 身份

    @Test
    @DisplayName("没有操作人身份 → 401（读也一样，白名单内容不该匿名可见）")
    void missingOperatorIsUnauthorized() throws Exception {
        mvc.perform(get("/api/tools/policies")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tools/pending")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/tools/audit")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("身份来自请求头，且无法识别的角色按 VIEWER 处理")
    void unknownRoleBecomesViewer() throws Exception {
        mvc.perform(get("/api/tools/policies")
                        .header("X-Operator", "alice").header("X-Operator-Role", "SUPERUSER"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ 读

    @Test
    @DisplayName("列出白名单：按工具名排序，且不含参数 schema 的内容")
    void policiesAreListedSorted() throws Exception {
        givenPolicies(mcpHigh(), new ToolPolicy(LOCAL_TOOL, ToolSource.LOCAL, RiskLevel.LOW,
                true, Duration.ofMinutes(5), false, "{\"type\":\"object\"}"));

        // 期望顺序按字符序：'m'(0x6D) < 's'(0x73)，所以 "mcp:..." 在前。
        // 这里刻意用常量而不是字面量写死顺序，免得改前缀时断言静默失真。
        assertThat(MCP_TOOL).as("排序前提：MCP 前缀名应排在本地名之前")
                .isLessThan(LOCAL_TOOL);

        mvc.perform(get("/api/tools/policies")
                        .header("X-Operator", "alice").header("X-Operator-Role", "VIEWER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toolName").value(MCP_TOOL))
                .andExpect(jsonPath("$[0].hasArgsSchema").value(false))
                .andExpect(jsonPath("$[1].toolName").value(LOCAL_TOOL))
                .andExpect(jsonPath("$[1].hasArgsSchema").value(true));
    }

    @Test
    @DisplayName("审计接口：limit 越界 → 400")
    void auditLimitIsBounded() throws Exception {
        mvc.perform(get("/api/tools/audit").param("limit", "0")
                        .header("X-Operator", "alice").header("X-Operator-Role", "VIEWER"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/tools/audit").param("limit", "501")
                        .header("X-Operator", "alice").header("X-Operator-Role", "VIEWER"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ 预演

    @Test
    @DisplayName("preview 只预演，不落地、不留审计、不生成单子")
    void previewDoesNotApply() throws Exception {
        mvc.perform(post("/api/tools/policies/preview")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(MCP_TOOL, "扩容需要"))
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.widens").value(true))
                .andExpect(jsonPath("$.reasons").isNotEmpty());

        assertThat(engine.find(MCP_TOOL)).isEmpty();
        assertThat(store.size()).isZero();
        assertThat(audit.size()).isZero();
    }

    // ------------------------------------------------------------------ 发起

    @Test
    @DisplayName("放宽变更 → 202 + 待复核单号 + 判定理由")
    void wideningChangeReturnsAccepted() throws Exception {
        mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(MCP_TOOL, "扩容需要"))
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.requiresReview").value(true))
                .andExpect(jsonPath("$.ticketId").isNotEmpty())
                .andExpect(jsonPath("$.reasons").isNotEmpty());

        assertThat(engine.find(MCP_TOOL)).as("复核通过前绝不能生效").isEmpty();
    }

    @Test
    @DisplayName("收紧变更 → 200 直接生效，不需要第二个人")
    void tighteningChangeReturnsOk() throws Exception {
        givenPolicies(mcpHigh());

        mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(revokeBody(MCP_TOOL, "工具已下线"))
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.requiresReview").value(false));

        assertThat(engine.find(MCP_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("缺变更理由 → 400")
    void missingReasonIsBadRequest() throws Exception {
        mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(MCP_TOOL, ""))
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("非法 kind → 400，而不是 Jackson 的内部消息")
    void invalidKindIsBadRequest() throws Exception {
        mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DELETE\",\"toolName\":\"x\",\"reason\":\"r\"}")
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("BAD_REQUEST"));
    }

    @Test
    @DisplayName("MCP 来源但名字没有 mcp: 前缀 → 400（前缀是安全边界）")
    void mcpSourceRequiresPrefix() throws Exception {
        mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody("scale", "r"))
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("LOCAL 来源却带 mcp: 前缀 → 400（否则一条本地策略就能给远端工具背书）")
    void localSourceForbidsPrefix() throws Exception {
        mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"GRANT\",\"toolName\":\"" + MCP_TOOL
                                + "\",\"source\":\"LOCAL\",\"risk\":\"READ_ONLY\",\"reason\":\"r\"}")
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("对已存在的工具发 GRANT → 400（界面写「新增」实际做「覆盖」是最坏的误导）")
    void grantOnExistingToolIsBadRequest() throws Exception {
        givenPolicies(mcpHigh());

        mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(MCP_TOOL, "r"))
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VIEWER 不能发起变更 → 403 而不是 400（角色不够，不是参数写错）")
    void viewerCannotPropose() throws Exception {
        mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(MCP_TOOL, "想加个工具"))
                        .header("X-Operator", "dave").header("X-Operator-Role", "VIEWER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    // ------------------------------------------------------------------ 复核

    @Test
    @DisplayName("另一个 ADMIN 复核通过 → 200，策略生效")
    void secondAdminCanApprove() throws Exception {
        String id = propose();

        mvc.perform(post("/api/tools/pending/" + id + "/confirm").param("reason", "已确认影响面")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));

        assertThat(engine.find(MCP_TOOL)).isPresent();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("自审 → 409 且机器码是 SELF_APPROVAL（前端据此提示「换人」）")
    void selfApprovalIsConflictWithItsOwnCode() throws Exception {
        String id = propose("alice", "ADMIN");

        mvc.perform(post("/api/tools/pending/" + id + "/confirm")
                        .header("X-Operator", "alice").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SELF_APPROVAL"));
        assertThat(engine.find(MCP_TOOL)).isEmpty();
    }

    @Test
    @DisplayName("EDITOR 复核 → 403 且机器码是 NOT_AUTHORIZED")
    void editorCannotApprove() throws Exception {
        String id = propose();

        mvc.perform(post("/api/tools/pending/" + id + "/confirm")
                        .header("X-Operator", "carol").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("NOT_AUTHORIZED"));
    }

    @Test
    @DisplayName("过期 → 410 且机器码是 EXPIRED（前端据此提示「重新发起」）")
    void expiredIsGone() throws Exception {
        String id = propose();
        now.addAndGet(ToolPolicyGovernance.TICKET_TTL_MILLIS + 1);

        mvc.perform(post("/api/tools/pending/" + id + "/confirm")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("EXPIRED"));
    }

    @Test
    @DisplayName("发起后策略被改过 → 409 且机器码是 STALE")
    void staleIsConflictWithItsOwnCode() throws Exception {
        givenPolicies(mcpHigh());
        String id = proposeUpdateToLow();
        // 期间一个收紧变更直接落地了（收紧不需要复核）
        governance.propose(com.oncall.toolgateway.governance.ToolPolicyChange.update(
                        new ToolPolicy(MCP_TOOL, ToolSource.MCP, RiskLevel.HIGH,
                                true, Duration.ofMinutes(15), true, null)),
                new com.oncall.domain.governance.Operator("carol", com.oncall.domain.governance.Operator.Role.EDITOR),
                "加一道双人复核");

        mvc.perform(post("/api/tools/pending/" + id + "/confirm")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE"));
    }

    @Test
    @DisplayName("不存在的单子 → 404")
    void unknownTicketIsNotFound() throws Exception {
        mvc.perform(post("/api/tools/pending/nope/confirm")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("驳回 → 204，且单子消失")
    void rejectReturnsNoContent() throws Exception {
        String id = propose();

        mvc.perform(post("/api/tools/pending/" + id + "/reject").param("reason", "算了")
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isNoContent());

        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("无关第三人不能驳回 → 403，且单子还在")
    void unrelatedPersonCannotReject() throws Exception {
        String id = propose();

        mvc.perform(post("/api/tools/pending/" + id + "/reject")
                        .header("X-Operator", "dave").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        assertThat(store.size()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 列表与审计

    @Test
    @DisplayName("未决单子列表：发起人、工具名、变更描述都在")
    void pendingListIsVisible() throws Exception {
        propose();

        mvc.perform(get("/api/tools/pending")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].toolName").value(MCP_TOOL))
                .andExpect(jsonPath("$[0].kind").value("GRANT"))
                .andExpect(jsonPath("$[0].requester").value("alice"))
                .andExpect(jsonPath("$[0].id").isNotEmpty());
    }

    @Test
    @DisplayName("审计里能看到「谁在什么时候放行了什么」，含发起与复核两条")
    void auditRecordsProposerAndReviewer() throws Exception {
        String id = propose();
        mvc.perform(post("/api/tools/pending/" + id + "/confirm")
                .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/tools/audit")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].actor").value("bob"))
                .andExpect(jsonPath("$[0].outcome").value("APPLIED_AFTER_REVIEW"))
                .andExpect(jsonPath("$[1].actor").value("alice"))
                .andExpect(jsonPath("$[1].outcome").value("PROPOSED"))
                .andExpect(jsonPath("$[0].toolName").value(MCP_TOOL));
    }

    @Test
    @DisplayName("请求体不是合法 JSON → 400，不是 500")
    void malformedJsonIsBadRequest() throws Exception {
        mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json")
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private String propose() throws Exception {
        return propose("alice", "EDITOR");
    }

    private String propose(String who, String role) throws Exception {
        String body = mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(grantBody(MCP_TOOL, "扩容需要"))
                        .header("X-Operator", who).header("X-Operator-Role", role))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        int i = body.indexOf("\"ticketId\":\"") + "\"ticketId\":\"".length();
        return body.substring(i, body.indexOf('"', i));
    }

    private String proposeUpdateToLow() throws Exception {
        String body = mvc.perform(post("/api/tools/policies")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"UPDATE\",\"toolName\":\"" + MCP_TOOL
                                + "\",\"source\":\"MCP\",\"risk\":\"LOW\",\"requiresApproval\":true,"
                                + "\"approvalTimeoutSeconds\":900,\"reason\":\"降级\"}")
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        int i = body.indexOf("\"ticketId\":\"") + "\"ticketId\":\"".length();
        return body.substring(i, body.indexOf('"', i));
    }

    /**
     * HIGH→LOW 是<b>放宽</b>方向，所以 UPDATE 降级必须走复核。
     *
     * <p>这条容易写反：直觉上"降级"像是收紧。风险等级的语义是
     * <b>数值越大越危险</b>，HIGH→LOW 意味着原本要人工审批的动作现在不要了——
     * 那是提权。写这个测试的时候我自己就先想反了一次。
     */
    @Test
    @DisplayName("HIGH→LOW 是放宽方向，所以 UPDATE 降级必须走复核")
    void downgradeNeedsReview() throws Exception {
        givenPolicies(mcpHigh());

        proposeUpdateToLow();   // 内部已断言 202，即「被判定为需要复核」

        assertThat(engine.find(MCP_TOOL).orElseThrow().risk())
                .as("复核通过前风险等级不能变").isEqualTo(RiskLevel.HIGH);
        assertThat(governance.openTickets()).hasSize(1);
        assertThat(governance.openTickets().get(0).change().proposed())
                .isEqualTo(mcpLow());
    }
}
