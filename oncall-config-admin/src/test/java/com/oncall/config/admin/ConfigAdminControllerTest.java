package com.oncall.config.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oncall.config.ConfigRegistry;
import com.oncall.config.ConfigService;
import com.oncall.config.InMemoryConfigAuditLog;
import com.oncall.config.InMemoryConfigStore;
import com.oncall.config.OnCallConfigKeys;
import com.oncall.config.OnCallConfigRegistry;
import com.oncall.config.schema.ConfigSchemaExporter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理接口的行为测试。
 *
 * <p>用 {@code standaloneSetup} 而不是 {@code @SpringBootTest}：不起容器，
 * 只验证路由、参数绑定、状态码与异常翻译。快一个数量级，而且失败时
 * 一眼能看出是控制器的问题还是装配的问题。
 *
 * <p><b>断言刻意避开中文文案</b>：{@code MockHttpServletResponse} 在未显式声明
 * charset 时按 ISO-8859-1 解码，直接比对中文串会因为编码而不是因为逻辑失败。
 * 所以只对状态码与 ASCII 字段做精确断言，需要看文案时显式用 UTF-8 取。
 */
@DisplayName("配置管理 REST 接口")
class ConfigAdminControllerTest {

    private static final String TOP_N = OnCallConfigKeys.RETRIEVAL_TOP_N;          // 普通项
    private static final String MAX_STEPS = OnCallConfigKeys.AGENT_MAX_STEPS;      // 高危项
    private static final String AUTONOMY = OnCallConfigKeys.AUTONOMY_LEVEL;        // 高危项
    private static final String DIMENSION = OnCallConfigKeys.VECTOR_DIMENSION;     // BACKEND_ONLY

    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong now = new AtomicLong(1_000_000L);

    private ConfigRegistry registry;
    private ConfigService service;
    private InMemoryConfigAuditLog auditLog;
    private InMemoryPendingChangeStore pendingStore;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        registry = OnCallConfigRegistry.create();
        InMemoryConfigStore store = new InMemoryConfigStore();
        auditLog = new InMemoryConfigAuditLog();
        service = new ConfigService(registry, store, auditLog);
        pendingStore = new InMemoryPendingChangeStore();
        mvc = MockMvcBuilders.standaloneSetup(new ConfigAdminController(
                        service, registry, new ConfigSchemaExporter(service),
                        auditLog, pendingStore, new ConfigAccessPolicy(), now::get))
                .setControllerAdvice(new ConfigAdminExceptionHandler())
                .build();
    }

    // ------------------------------------------------------------------ 读

    @Test
    @DisplayName("列表不含 BACKEND_ONLY 项")
    void viewsExcludeBackendOnly() throws Exception {
        String body = mvc.perform(get("/api/config/views"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains(TOP_N).doesNotContain(DIMENSION);
    }

    @Test
    @DisplayName("高危项在列表里带 requiresApproval 标记，前端可以提前提示")
    void viewsFlagHighRisk() throws Exception {
        mvc.perform(get("/api/config/views"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key=='" + MAX_STEPS + "')].requiresApproval")
                        .value(org.hamcrest.Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.key=='" + TOP_N + "')].requiresApproval")
                        .value(org.hamcrest.Matchers.hasItem(false)));
    }

    @Test
    @DisplayName("BACKEND_ONLY 与未声明的键返回同一个 404：不能通过状态码差异枚举后端键")
    void backendOnlyAndUnknownAreIndistinguishable() throws Exception {
        int backendOnly = mvc.perform(get("/api/config/items/" + DIMENSION))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getStatus();
        int unknown = mvc.perform(get("/api/config/items/no.such.key"))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getStatus();
        assertThat(backendOnly).isEqualTo(unknown);

        // 历史接口同样不能成为枚举通道
        mvc.perform(get("/api/config/items/" + DIMENSION + "/history"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("schema 端点返回合法 JSON")
    void schemaIsValidJson() throws Exception {
        MvcResult r = mvc.perform(get("/api/config/schema"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(node.isObject() || node.isArray()).isTrue();
    }

    // ------------------------------------------------------------------ 写

    @Test
    @DisplayName("缺少身份头 → 401")
    void missingOperatorIsUnauthorized() throws Exception {
        mvc.perform(put("/api/config/items/" + TOP_N)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"8\",\"reason\":\"t\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("VIEWER 改配置 → 403")
    void viewerCannotWrite() throws Exception {
        mvc.perform(put("/api/config/items/" + TOP_N)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "bob").header("X-Operator-Role", "VIEWER")
                        .content("{\"value\":\"8\",\"reason\":\"t\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("缺变更理由 → 400：没有理由的变更无法审计")
    void missingReasonIsRejected() throws Exception {
        mvc.perform(put("/api/config/items/" + TOP_N)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR")
                        .content("{\"value\":\"8\",\"reason\":\"  \"}"))
                .andExpect(status().isBadRequest());
        assertThat(service.get(TOP_N)).isEqualTo("5");
    }

    @Test
    @DisplayName("越界的值 → 400，且不写库不审计")
    void outOfBoundsValueIsRejected() throws Exception {
        mvc.perform(put("/api/config/items/" + TOP_N)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR")
                        .content("{\"value\":\"999\",\"reason\":\"t\"}"))
                .andExpect(status().isBadRequest());
        assertThat(service.get(TOP_N)).isEqualTo("5");
        assertThat(auditLog.history(TOP_N)).isEmpty();
    }

    @Test
    @DisplayName("EDITOR 改普通项 → 200 生效并留下审计")
    void editorCanWriteNormalKey() throws Exception {
        mvc.perform(put("/api/config/items/" + TOP_N)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR")
                        .content("{\"value\":\"8\",\"reason\":\"召回不足\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.oldValue").value("5"))
                .andExpect(jsonPath("$.newValue").value("8"));
        assertThat(service.get(TOP_N)).isEqualTo("8");
        assertThat(auditLog.history(TOP_N)).hasSize(1);
        assertThat(auditLog.history(TOP_N).get(0).operator()).isEqualTo("alice");
    }

    @Test
    @DisplayName("写入相同值 → 200 但不产生审计：避免反复点保存制造噪音")
    void writingSameValueProducesNoAudit() throws Exception {
        mvc.perform(put("/api/config/items/" + TOP_N)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR")
                        .content("{\"value\":\"5\",\"reason\":\"t\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true));
        assertThat(auditLog.history(TOP_N)).isEmpty();
    }

    @Test
    @DisplayName("DELETE 恢复默认值；缺 reason 参数 → 400")
    void deleteResetsToDefault() throws Exception {
        service.set(TOP_N, "8", "alice", "先改一下", true);

        mvc.perform(delete("/api/config/items/" + TOP_N)
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isBadRequest());   // reason 是必需参数

        mvc.perform(delete("/api/config/items/" + TOP_N)
                        .param("reason", "回到基线")
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.newValue").value("5"));
        assertThat(service.get(TOP_N)).isEqualTo("5");
    }

    // ------------------------------------------------------- 双人复核

    @Test
    @DisplayName("高危项：EDITOR 保存只生成待复核单，值不会变")
    void highRiskWriteCreatesPendingOnly() throws Exception {
        mvc.perform(put("/api/config/items/" + MAX_STEPS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR")
                        .content("{\"value\":\"20\",\"reason\":\"步骤不够用\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.applied").value(false))
                .andExpect(jsonPath("$.requiresApproval").value(true))
                .andExpect(jsonPath("$.pendingChangeId").isNotEmpty());

        assertThat(service.get(MAX_STEPS)).isEqualTo("10");   // 没生效
        assertThat(pendingStore.size()).isEqualTo(1);
        assertThat(auditLog.history(MAX_STEPS)).isEmpty();     // 也还没审计
    }

    @Test
    @DisplayName("不能复核自己发起的变更——否则双人复核就是同一个人点两次")
    void cannotApproveOwnChange() throws Exception {
        String id = propose(MAX_STEPS, "20", "alice", "EDITOR");
        mvc.perform(post("/api/config/pending/" + id + "/confirm")
                        .header("X-Operator", "alice").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isConflict());
        assertThat(service.get(MAX_STEPS)).isEqualTo("10");
    }

    @Test
    @DisplayName("EDITOR 无复核权 → 403")
    void editorCannotApprove() throws Exception {
        String id = propose(MAX_STEPS, "20", "alice", "EDITOR");
        mvc.perform(post("/api/config/pending/" + id + "/confirm")
                        .header("X-Operator", "carol").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("另一个 ADMIN 复核通过 → 生效，且审计同时记录发起人与复核人")
    void secondAdminCanApprove() throws Exception {
        String id = propose(AUTONOMY, "SUGGEST", "alice", "EDITOR");
        mvc.perform(post("/api/config/pending/" + id + "/confirm")
                        .param("reason", "已确认监控覆盖")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.applied").value(true))
                .andExpect(jsonPath("$.newValue").value("SUGGEST"));

        assertThat(service.get(AUTONOMY)).isEqualTo("SUGGEST");
        assertThat(pendingStore.size()).isZero();
        // 审计里要能还原出这是双人流程：操作人是复核人，理由里同时留着发起理由与复核意见
        var change = auditLog.history(AUTONOMY).get(0);
        assertThat(change.operator()).isEqualTo("bob");
        assertThat(change.reason()).contains("发起理由").contains("已确认监控覆盖");
    }

    @Test
    @DisplayName("复核单只能用一次：重复复核 → 404")
    void pendingIsSingleUse() throws Exception {
        String id = propose(MAX_STEPS, "20", "alice", "EDITOR");
        mvc.perform(post("/api/config/pending/" + id + "/confirm")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/config/pending/" + id + "/confirm")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("过期的复核单 → 410：隔久了系统状态变了，当时的判断不再成立")
    void expiredPendingIsGone() throws Exception {
        String id = propose(MAX_STEPS, "20", "alice", "EDITOR");
        now.addAndGet(ConfigAccessPolicy.PENDING_TTL_MILLIS + 1);
        mvc.perform(post("/api/config/pending/" + id + "/confirm")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isGone());
        assertThat(service.get(MAX_STEPS)).isEqualTo("10");
    }

    @Test
    @DisplayName("发起后该键被别处改过 → 409，复核依据已失效")
    void stalePendingIsRejected() throws Exception {
        String id = propose(MAX_STEPS, "20", "alice", "EDITOR");
        // 模拟后端内部改动（fromUi=false），例如兜底逻辑自行调整
        service.set(MAX_STEPS, "15", "backend", "内部调整", false);
        mvc.perform(post("/api/config/pending/" + id + "/confirm")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isConflict());
        assertThat(service.get(MAX_STEPS)).isEqualTo("15");   // 保持后来者写入的值
        assertThat(pendingStore.size()).isZero();              // 失效单被清掉
    }

    @Test
    @DisplayName("驳回：发起人自己或 ADMIN 都可以，驳回后不生效")
    void rejectWorks() throws Exception {
        String id = propose(MAX_STEPS, "20", "alice", "EDITOR");
        mvc.perform(post("/api/config/pending/" + id + "/reject")
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isNoContent());
        assertThat(service.get(MAX_STEPS)).isEqualTo("10");
        assertThat(pendingStore.size()).isZero();
    }

    @Test
    @DisplayName("无关的第三人不能驳回")
    void unrelatedPersonCannotReject() throws Exception {
        String id = propose(MAX_STEPS, "20", "alice", "EDITOR");
        mvc.perform(post("/api/config/pending/" + id + "/reject")
                        .header("X-Operator", "dave").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("不存在的复核单 → 404")
    void unknownPendingIs404() throws Exception {
        mvc.perform(post("/api/config/pending/nope/confirm")
                        .header("X-Operator", "bob").header("X-Operator-Role", "ADMIN"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------- 其他

    @Test
    @DisplayName("recent 的 limit 有上界：不能让一个请求把整张审计表拉走")
    void recentLimitIsBounded() throws Exception {
        mvc.perform(get("/api/config/changes/recent").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/config/changes/recent").param("limit", "5000"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/config/changes/recent").param("limit", "10"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("VIEWER 不能触发重载")
    void viewerCannotReload() throws Exception {
        mvc.perform(post("/api/config/reload")
                        .header("X-Operator", "bob").header("X-Operator-Role", "VIEWER"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/config/reload")
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("请求体不是合法 JSON → 400 而不是 500")
    void malformedBodyIs400() throws Exception {
        mvc.perform(put("/api/config/items/" + TOP_N)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", "alice").header("X-Operator-Role", "EDITOR")
                        .content("{not json"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------- 辅助

    private String propose(String key, String value, String who, String role) throws Exception {
        MvcResult r = mvc.perform(put("/api/config/items/" + key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Operator", who).header("X-Operator-Role", role)
                        .content("{\"value\":\"" + value + "\",\"reason\":\"发起理由\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("pendingChangeId").asText();
    }
}
