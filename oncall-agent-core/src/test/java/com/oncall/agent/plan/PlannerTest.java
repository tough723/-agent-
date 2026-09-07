package com.oncall.agent.plan;

import com.oncall.agent.llm.StubChatModel;
import com.oncall.agent.prompt.ActiveVersionSource;
import com.oncall.agent.prompt.PromptRegistry;
import com.oncall.domain.plan.BasisType;
import com.oncall.domain.plan.Plan;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.toolgateway.ToolPolicyEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Planner} 的契约。
 *
 * <p>被测对象是<b>真的</b>：真的 {@code PromptRegistry}（从 classpath 读到生产那份
 * {@code plan-generate.v1.md}）、真的 {@code PlanValidator}、真的 {@code ToolPolicyEngine}。
 * 只有模型是替身。
 *
 * <h2>★ 本类守的核心区分</h2>
 * <p>{@link PlanProductionException}（没有计划）与 {@link PlanRejectedException}
 * （有计划但不许执行）必须是两个类。混成一个，
 * 「模型最近老是不返回」和「模型老是想跳过探查直接扩容」
 * 在告警面板上就长得一模一样。
 */
@DisplayName("Planner：产出即校验，失败即抛出（无降级）")
class PlannerTest {

    private static final String READ = "k8s_get_pods";
    private static final String HIGH = "scale_replicas";

    /** 三步只读探查，全部有可信依据 —— 能通过 PlanValidator。 */
    private static final String OK_JSON = """
            {"steps":[
              {"seq":1,"action":"k8s_get_pods","args":{"service":"payment-api"},
               "basis":[{"type":"ALERT_RULE","ref":"rule-1"}]},
              {"seq":2,"action":"k8s_get_events","args":{},
               "basis":[{"type":"ALERT_RULE","ref":"rule-1"}]},
              {"seq":3,"action":"k8s_get_logs","args":{},
               "basis":[{"type":"RUNBOOK","ref":"rb-1"}]}
            ]}""";

    private static Planner planner(StubChatModel model) {
        PromptRegistry prompts = PromptRegistry.fromClasspath(
                List.of("plan-generate"),
                ActiveVersionSource.fixed(Map.of("plan-generate", "v1")));
        ToolPolicyEngine engine = new ToolPolicyEngine(List.of(
                ToolPolicy.readOnly("k8s_get_pods"),
                ToolPolicy.readOnly("k8s_get_events"),
                ToolPolicy.readOnly("k8s_get_logs"),
                ToolPolicy.highRisk(HIGH, Duration.ofMinutes(10))));
        return new Planner(model, prompts, new PlanValidator(engine, 3));
    }

    private static List<String> tools() {
        return List.of("k8s_get_pods", "k8s_get_events", "k8s_get_logs", HIGH);
    }

    @Test
    @DisplayName("正常产出：prompt 真的渲染进了告警与工具清单")
    void producesAPlanAndRendersThePrompt() {
        StubChatModel model = StubChatModel.returning(OK_JSON);
        Plan plan = planner(model).plan("payment-api P99 延迟升高", tools());

        assertThat(plan.size()).isEqualTo(3);
        assertThat(plan.step(1).action()).isEqualTo("k8s_get_pods");
        assertThat(plan.step(1).argsJson()).contains("payment-api");
        assertThat(plan.step(3).basisRefs().get(0).type()).isEqualTo(BasisType.RUNBOOK);

        String sent = model.lastPrompt();
        assertThat(sent).contains("payment-api P99 延迟升高");
        assertThat(sent).as("工具清单必须渲染进 prompt，否则模型只能靠猜")
                .contains("k8s_get_pods").contains(HIGH);
        assertThat(sent).as("占位符不应残留在发出去的 prompt 里")
                .doesNotContain("{{alert}}").doesNotContain("{{available_tools}}");
    }

    @Test
    @DisplayName("★ seq 按数组顺序重新编号，不采信模型给的 seq")
    void renumbersSeqInsteadOfTrustingTheModel() {
        // 模型给了 0 起、跳号的 seq
        StubChatModel model = StubChatModel.returning("""
                {"steps":[
                  {"seq":0,"action":"k8s_get_pods","args":{},
                   "basis":[{"type":"ALERT_RULE","ref":"r"}]},
                  {"seq":7,"action":"k8s_get_events","args":{},
                   "basis":[{"type":"ALERT_RULE","ref":"r"}]}
                ]}""");
        Plan plan = planner(model).plan("告警", tools());
        assertThat(plan.step(1).action()).isEqualTo("k8s_get_pods");
        assertThat(plan.step(2).action()).isEqualTo("k8s_get_events");
    }

    @Test
    @DisplayName("★ 模型自创 basis.type 必须拒绝，不能默认成某个值")
    void inventedBasisTypeIsRejected() {
        StubChatModel model = StubChatModel.returning("""
                {"steps":[{"seq":1,"action":"k8s_get_pods","args":{},
                  "basis":[{"type":"ADMIN_APPROVAL","ref":"someone said ok"}]}]}""");
        assertThatThrownBy(() -> planner(model).plan("告警", tools()))
                .isInstanceOf(PlanProductionException.class)
                .hasMessageContaining("不是合法取值");
    }

    @Test
    @DisplayName("★ 缺少 basis 数组即拒——每步都必须声明依据")
    void missingBasisIsRejected() {
        StubChatModel model = StubChatModel.returning(
                "{\"steps\":[{\"seq\":1,\"action\":\"k8s_get_pods\",\"args\":{}}]}");
        assertThatThrownBy(() -> planner(model).plan("告警", tools()))
                .isInstanceOf(PlanProductionException.class)
                .hasMessageContaining("缺少 basis 数组");
    }

    @Test
    @DisplayName("★ action 为 JSON null 时不得变成字面量字符串 \"null\"")
    void nullActionDoesNotBecomeTheStringNull() {
        StubChatModel model = StubChatModel.returning(
                "{\"steps\":[{\"seq\":1,\"action\":null,\"args\":{},\"basis\":[]}]}");
        assertThatThrownBy(() -> planner(model).plan("告警", tools()))
                .isInstanceOf(PlanProductionException.class)
                .hasMessageContaining("缺少 action");
    }

    @Test
    @DisplayName("★ 产出但违反静态校验 → PlanRejectedException（不是 Production）")
    void unsafePlanIsRejectedNotUnproducible() {
        // 一步高危、零探查：能解析成 Plan，但过不了 PlanValidator
        StubChatModel model = StubChatModel.returning("""
                {"steps":[{"seq":1,"action":"scale_replicas","args":{"replicas":9},
                  "basis":[{"type":"ALERT_RULE","ref":"r"}]}]}""");
        assertThatThrownBy(() -> planner(model).plan("告警", tools()))
                .isInstanceOf(PlanRejectedException.class)
                .as("有计划但不许执行，必须与「没有计划」区分开")
                .isNotInstanceOf(PlanProductionException.class);
    }

    @Test
    @DisplayName("★ 无降级：模型抛异常 / 空响应 / 非 JSON 都是 PlanProductionException")
    void noFallbackOnAnyModelFailure() {
        assertThatThrownBy(() -> planner(StubChatModel.returning(""))
                .plan("告警", tools()))
                .isInstanceOf(PlanProductionException.class)
                .hasMessageContaining("空响应");

        assertThatThrownBy(() -> planner(StubChatModel.returning("这不是 JSON"))
                .plan("告警", tools()))
                .isInstanceOf(PlanProductionException.class);

        assertThatThrownBy(() -> planner(StubChatModel.returning("{\"nope\":1}"))
                .plan("告警", tools()))
                .isInstanceOf(PlanProductionException.class)
                .hasMessageContaining("缺少 steps 数组");
    }

    @Test
    @DisplayName("代码围栏会被剥掉——模型常加 ```json")
    void stripsCodeFences() {
        StubChatModel model = StubChatModel.returning("```json\n" + OK_JSON + "\n```");
        assertThat(planner(model).plan("告警", tools()).size()).isEqualTo(3);
    }

    @Test
    @DisplayName("入参校验：alert 空白与空工具清单都在调用模型之前就拒")
    void rejectsBadInputsBeforeCallingTheModel() {
        StubChatModel model = StubChatModel.returning(OK_JSON);
        assertThatThrownBy(() -> planner(model).plan("  ", tools()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("alert");
        assertThatThrownBy(() -> planner(model).plan("告警", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("availableTools 为空");
        assertThat(model.callCount()).as("不该为了校验入参而白跑一次模型").isZero();
    }
}
