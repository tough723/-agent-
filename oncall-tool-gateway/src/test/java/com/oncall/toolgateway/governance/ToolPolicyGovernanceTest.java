package com.oncall.toolgateway.governance;

import com.oncall.domain.governance.Operator;
import com.oncall.domain.governance.ReviewVerdict;
import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.tool.ToolSource;
import com.oncall.toolgateway.ToolPolicyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具策略变更治理的验收测试。
 *
 * <p>覆盖的是那条原本敞开的路：加一条工具策略等于放行一个工具，
 * 而在此之前既没有第二人复核，也没有可查记录。
 *
 * <p><b>判定规则本身不在这里测</b>——它在 {@code TwoPersonReviewTest} 里，
 * 与配置侧共用同一份。这里测的是接线：什么时候生成单子、
 * 什么时候直接生效、单子什么时候被清掉、审计里留下了什么。
 */
class ToolPolicyGovernanceTest {

    private static final String TOOL = "mcp:cmdb:scale";
    private static final Operator ALICE_EDITOR = new Operator("alice", Operator.Role.EDITOR);
    private static final Operator BOB_ADMIN = new Operator("bob", Operator.Role.ADMIN);

    private ToolPolicyEngine engine;
    private InMemoryToolPolicyChangeTicketStore store;
    private InMemoryToolPolicyChangeAudit audit;
    private AtomicLong now;
    private ToolPolicyGovernance governance;

    @BeforeEach
    void setUp() {
        engine = new ToolPolicyEngine(List.of());
        store = new InMemoryToolPolicyChangeTicketStore();
        audit = new InMemoryToolPolicyChangeAudit();
        now = new AtomicLong(1_000_000L);
        governance = new ToolPolicyGovernance(engine, store, audit, now::get);
    }

    private static ToolPolicy highRisk() {
        return new ToolPolicy(TOOL, ToolSource.MCP, RiskLevel.HIGH,
                true, Duration.ofMinutes(15), false, null);
    }

    private static ToolPolicy lowRisk() {
        return new ToolPolicy(TOOL, ToolSource.MCP, RiskLevel.LOW,
                true, Duration.ofMinutes(15), false, null);
    }

    // ------------------------------------------------------------------ 发起

    @Test
    @DisplayName("放宽（新增工具）只生成待复核单，不生效")
    void wideningChangeOnlyCreatesATicket() {
        ToolPolicyGovernance.Proposal p =
                governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "扩容需要");

        assertThat(p.appliedDirectly()).isFalse();
        assertThat(p.ticket()).isNotNull();
        assertThat(p.delta().widens()).isTrue();
        assertThat(engine.find(TOOL)).as("复核通过前绝不能生效").isEmpty();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("收紧（撤销工具）立即生效，不需要第二个人")
    void tighteningChangeAppliesDirectly() {
        engine.register(highRisk());

        ToolPolicyGovernance.Proposal p =
                governance.propose(ToolPolicyChange.revoke(TOOL), ALICE_EDITOR, "工具已下线");

        assertThat(p.appliedDirectly()).isTrue();
        assertThat(p.ticket()).isNull();
        assertThat(engine.find(TOOL)).isEmpty();
        assertThat(store.size()).as("收紧不该产生待复核单").isZero();
    }

    @Test
    @DisplayName("把风险从 LOW 提到 HIGH 是收紧，直接生效")
    void raisingRiskAppliesDirectly() {
        engine.register(lowRisk());

        assertThat(governance.propose(ToolPolicyChange.update(highRisk()), ALICE_EDITOR, "提级")
                .appliedDirectly()).isTrue();
        assertThat(engine.find(TOOL).orElseThrow().risk()).isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("把风险从 HIGH 降到 LOW 是放宽，必须两人")
    void loweringRiskNeedsReview() {
        engine.register(highRisk());

        assertThat(governance.propose(ToolPolicyChange.update(lowRisk()), ALICE_EDITOR, "嫌审批麻烦")
                .appliedDirectly()).isFalse();
        assertThat(engine.find(TOOL).orElseThrow().risk())
                .as("复核通过前风险等级不能变").isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("VIEWER 不能发起变更")
    void viewerCannotPropose() {
        assertThatThrownBy(() -> governance.propose(ToolPolicyChange.grant(highRisk()),
                new Operator("dave", Operator.Role.VIEWER), "想加个工具"))
                .isInstanceOf(GovernanceException.class)
                .hasMessageContaining("VIEWER");
    }

    @Test
    @DisplayName("没有理由的变更发起不了——没理由就无法审计，复核人也无从判断")
    void blankReasonRejected() {
        assertThatThrownBy(() -> governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("理由必填");
    }

    @Test
    @DisplayName("变更类型必须与实际状态对得上：对已存在的工具发 GRANT 被拒")
    void grantOnExistingToolRejected() {
        engine.register(highRisk());

        // 不挡的话，界面写"新增"、实际做"覆盖"，审计里留下的是发起人以为自己在做的事。
        assertThatThrownBy(() -> governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "r"))
                .isInstanceOf(GovernanceException.class)
                .hasMessageContaining("已在白名单里");
    }

    @Test
    @DisplayName("对不存在的工具发 UPDATE 被拒")
    void updateOnMissingToolRejected() {
        assertThatThrownBy(() -> governance.propose(ToolPolicyChange.update(highRisk()), ALICE_EDITOR, "r"))
                .isInstanceOf(GovernanceException.class)
                .hasMessageContaining("不在白名单里");
    }

    @Test
    @DisplayName("撤销一条不存在的策略是无害空操作，刻意不报错")
    void revokeOfMissingToolIsANoOp() {
        assertThat(governance.propose(ToolPolicyChange.revoke(TOOL), ALICE_EDITOR, "确保它不在")
                .appliedDirectly()).isTrue();
        assertThat(engine.find(TOOL)).isEmpty();
    }

    // ------------------------------------------------------------------ 复核

    @Test
    @DisplayName("另一个 ADMIN 复核通过 → 生效，审计同时留下发起人与复核人")
    void secondAdminCanApprove() {
        var ticket = governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "扩容需要")
                .ticket();

        governance.confirm(ticket.id(), BOB_ADMIN, "已确认影响面");

        assertThat(engine.find(TOOL)).isPresent();
        assertThat(store.size()).as("单子只能用一次").isZero();
        var entries = audit.history(TOOL);
        assertThat(entries).extracting(ToolPolicyChangeAudit.Entry::actor)
                .containsExactly("alice", "bob");
        assertThat(entries).extracting(ToolPolicyChangeAudit.Entry::outcome)
                .containsExactly(ToolPolicyChangeAudit.Outcome.PROPOSED,
                        ToolPolicyChangeAudit.Outcome.APPLIED_AFTER_REVIEW);
    }

    @Test
    @DisplayName("自审被拒：策略不生效，但单子留着——换个人就该能复核")
    void selfApprovalRejectedAndTicketSurvives() {
        var alice = new Operator("alice", Operator.Role.ADMIN);
        var ticket = governance.propose(ToolPolicyChange.grant(highRisk()), alice, "扩容需要").ticket();

        assertThatThrownBy(() -> governance.confirm(ticket.id(), alice, null))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).verdict())
                        .isEqualTo(ReviewVerdict.SELF_APPROVAL));

        assertThat(engine.find(TOOL)).isEmpty();
        assertThat(store.size()).as("无权与自审不删单，换人即可复核").isEqualTo(1);
    }

    @Test
    @DisplayName("EDITOR 无复核权 → 拒绝，单子留着")
    void editorCannotApprove() {
        var ticket = governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "r").ticket();

        assertThatThrownBy(() -> governance.confirm(ticket.id(),
                new Operator("carol", Operator.Role.EDITOR), null))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).verdict())
                        .isEqualTo(ReviewVerdict.NOT_AUTHORIZED));
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("过期的单子 → 拒绝，且被清掉")
    void expiredTicketIsRejectedAndRemoved() {
        var ticket = governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "r").ticket();
        now.addAndGet(ToolPolicyGovernance.TICKET_TTL_MILLIS + 1);

        assertThatThrownBy(() -> governance.confirm(ticket.id(), BOB_ADMIN, null))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).verdict())
                        .isEqualTo(ReviewVerdict.EXPIRED));
        assertThat(engine.find(TOOL)).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("发起后策略被别处改过 → 复核依据失效，单子被清掉")
    void staleTicketIsRejectedAndRemoved() {
        engine.register(highRisk());
        // HIGH → LOW 是放宽，才会产生待复核单
        var ticket = governance.propose(ToolPolicyChange.update(lowRisk()), ALICE_EDITOR, "降级").ticket();
        // 模拟绕过治理的直接改动（register 目前仍是 public，见类注释里的已知缺口）
        engine.register(new ToolPolicy(TOOL, ToolSource.MCP, RiskLevel.READ_ONLY,
                false, Duration.ZERO, false, null));

        assertThatThrownBy(() -> governance.confirm(ticket.id(), BOB_ADMIN, null))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).verdict())
                        .isEqualTo(ReviewVerdict.STALE));
        assertThat(store.size()).isZero();
    }

    @Test
    @DisplayName("安全：无权复核的人不得从错误消息里读到当前策略")
    void unauthorizedReviewerCannotReadCurrentPolicy() {
        engine.register(highRisk());
        var ticket = governance.propose(ToolPolicyChange.update(lowRisk()), ALICE_EDITOR, "降级").ticket();
        // 发起之后把当前策略改成一个签名截然不同的值
        ToolPolicy changed = new ToolPolicy(TOOL, ToolSource.MCP, RiskLevel.READ_ONLY,
                false, Duration.ZERO, false, null);
        engine.register(changed);
        // 断言的对象是「当前签名」而不是某个 schema 片段：
        // 签名里只留了 schemaLen，若只断言 schema 内容不出现，
        // 那么即使判定顺序写反（STALE 排在角色之前）这条测试也照样绿——那不是有效断言。
        String currentSignature = ToolPolicyChangeTicket.signatureOf(changed);

        assertThatThrownBy(() -> governance.confirm(ticket.id(),
                new Operator("eve", Operator.Role.VIEWER), null))
                .isInstanceOf(GovernanceException.class)
                .hasMessageNotContaining(currentSignature)
                .hasMessageNotContaining("READ_ONLY");
    }

    @Test
    @DisplayName("单子只能用一次：重复复核 → 单据不存在")
    void ticketIsSingleUse() {
        var ticket = governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "r").ticket();
        governance.confirm(ticket.id(), BOB_ADMIN, null);

        assertThatThrownBy(() -> governance.confirm(ticket.id(), BOB_ADMIN, null))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).code())
                        .isEqualTo(GovernanceException.NOT_FOUND));
    }

    @Test
    @DisplayName("不存在的单子 → NOT_FOUND")
    void unknownTicketIsNotFound() {
        assertThatThrownBy(() -> governance.confirm("nope", BOB_ADMIN, null))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).code())
                        .isEqualTo(GovernanceException.NOT_FOUND));
    }

    // ------------------------------------------------------------------ 驳回

    @Test
    @DisplayName("发起人自己可以驳回")
    void requesterCanReject() {
        var ticket = governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "r").ticket();

        governance.reject(ticket.id(), ALICE_EDITOR, "算了");

        assertThat(store.size()).isZero();
        assertThat(audit.recent(1).get(0).outcome())
                .isEqualTo(ToolPolicyChangeAudit.Outcome.REJECTED);
    }

    @Test
    @DisplayName("无关第三人不能驳回")
    void unrelatedPersonCannotReject() {
        var ticket = governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "r").ticket();

        assertThatThrownBy(() -> governance.reject(ticket.id(),
                new Operator("dave", Operator.Role.EDITOR), null))
                .isInstanceOf(GovernanceException.class)
                .hasMessageContaining("只有复核人或发起人");
        assertThat(store.size()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 审计与并发

    @Test
    @DisplayName("被拒的尝试同样进审计——审计要能回答「谁试过放行什么」")
    void rejectedAttemptsAreAudited() {
        var alice = new Operator("alice", Operator.Role.ADMIN);
        var ticket = governance.propose(ToolPolicyChange.grant(highRisk()), alice, "r").ticket();

        assertThatThrownBy(() -> governance.confirm(ticket.id(), alice, null))
                .isInstanceOf(GovernanceException.class);

        assertThat(audit.history(TOOL))
                .extracting(ToolPolicyChangeAudit.Entry::outcome)
                .contains(ToolPolicyChangeAudit.Outcome.REJECTED);
        assertThat(audit.history(TOOL))
                .anyMatch(e -> e.changeDescription().contains(TOOL));
    }

    @Test
    @DisplayName("两个人同时发起同一个工具：第一张批了之后，第二张自动变成 STALE")
    void concurrentProposalsResolveByStaleness() {
        var t1 = governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "甲的理由").ticket();
        var t2 = governance.propose(ToolPolicyChange.grant(highRisk()),
                new Operator("carol", Operator.Role.EDITOR), "乙的理由").ticket();
        assertThat(t1.id()).isNotEqualTo(t2.id());

        governance.confirm(t1.id(), BOB_ADMIN, null);

        assertThatThrownBy(() -> governance.confirm(t2.id(), BOB_ADMIN, null))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).verdict())
                        .isEqualTo(ReviewVerdict.STALE));
    }

    @Test
    @DisplayName("未决单子按发起时间排序——顺序会进界面，不能抖")
    void openTicketsAreOrderedByCreationTime() {
        governance.propose(ToolPolicyChange.grant(highRisk()), ALICE_EDITOR, "第一张");
        now.addAndGet(1000);
        governance.propose(ToolPolicyChange.grant(
                new ToolPolicy("mcp:cmdb:restart", ToolSource.MCP, RiskLevel.HIGH,
                        true, Duration.ofMinutes(15), false, null)), ALICE_EDITOR, "第二张");

        assertThat(governance.openTickets())
                .extracting(t -> t.reason())
                .containsExactly("第一张", "第二张");
    }

    @Test
    @DisplayName("preview 只预演风险方向，绝不落地")
    void previewDoesNotApply() {
        PolicyRiskDelta d = governance.preview(ToolPolicyChange.grant(highRisk()));

        assertThat(d.widens()).isTrue();
        assertThat(engine.find(TOOL)).isEmpty();
        assertThat(store.size()).isZero();
        assertThat(audit.size()).as("预演不该留下审计").isZero();
    }

    @Test
    @DisplayName("协作者为 null 立即失败")
    void nullCollaboratorsRejected() {
        assertThatThrownBy(() -> new ToolPolicyGovernance(null, store, audit))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolPolicyGovernance(engine, null, audit))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new ToolPolicyGovernance(engine, store, null))
                .isInstanceOf(NullPointerException.class);
    }
}
