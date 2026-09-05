package com.oncall.domain.governance;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 双人复核决策核心的验收测试。
 *
 * <p><b>这个类测的重点不是"每条规则单独生效"，而是规则之间的先后关系。</b>
 * 四条规则单看都对，顺序错了才会出事：
 * 「已被改过」的提示里含当前生效值，若排在角色判定之前，
 * 一个 VIEWER 就能靠反复发起复核把后端专属配置的当前值一条条读出来。
 * 这类缺陷在功能测试里完全看不出来——所有正常路径都是绿的。
 *
 * <p>另外<b>每个判定值都必须有测试真的产出它</b>：
 * 一个从来没被触发过的枚举分支等于没写。
 */
class TwoPersonReviewTest {

    private static final long NOW = 1_000_000L;
    private static final long LATER = NOW + 60_000L;
    private static final String SUBJECT = "配置项 autonomy.level";
    private static final String CURRENT_SECRET = "BOUND_AUTO_SECRET";

    private static Operator admin(String name) {
        return new Operator(name, Operator.Role.ADMIN);
    }

    /** 一张还活着、没被改过的单子。 */
    private static ReviewRequest live(String requester, Operator reviewer) {
        return new ReviewRequest(SUBJECT, requester, LATER, "SUGGEST", "SUGGEST", reviewer);
    }

    // ------------------------------------------------------------------ 通过

    @Test
    @DisplayName("两个不同的 ADMIN：通过")
    void twoDifferentAdminsAreAllowed() {
        ReviewOutcome o = TwoPersonReview.evaluate(live("alice", admin("bob")), NOW);

        assertThat(o.allowed()).isTrue();
        assertThat(o.verdict()).isEqualTo(ReviewVerdict.ALLOWED);
        assertThat(o.message()).as("ALLOWED 时消息为空串而不是 null").isEmpty();
    }

    @Test
    @DisplayName("值从「无」变成「无」不算被改过")
    void bothNullIsNotStale() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", LATER, null, null, admin("bob"));

        assertThat(TwoPersonReview.evaluate(r, NOW).allowed()).isTrue();
    }

    // ------------------------------------------------------------------ 四条规则各自生效

    @Test
    @DisplayName("过期：到达过期时刻即失效（边界是 >=）")
    void expiredAtTheBoundary() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", NOW, "SUGGEST", "SUGGEST", admin("bob"));

        ReviewOutcome o = TwoPersonReview.evaluate(r, NOW);

        assertThat(o.verdict()).isEqualTo(ReviewVerdict.EXPIRED);
        assertThat(o.message()).contains("重新发起");
    }

    @Test
    @DisplayName("过期前一毫秒仍然有效")
    void notExpiredOneMillisecondBefore() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", NOW + 1, "SUGGEST", "SUGGEST", admin("bob"));

        assertThat(TwoPersonReview.evaluate(r, NOW).allowed()).isTrue();
    }

    @Test
    @DisplayName("EDITOR 不能复核——ADMIN 拥有的是复核权，不是别人也能代劳的权限")
    void editorCannotReview() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", LATER, "SUGGEST", "SUGGEST",
                new Operator("bob", Operator.Role.EDITOR));

        assertThat(TwoPersonReview.evaluate(r, NOW).verdict())
                .isEqualTo(ReviewVerdict.NOT_AUTHORIZED);
    }

    @Test
    @DisplayName("VIEWER 不能复核")
    void viewerCannotReview() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", LATER, "SUGGEST", "SUGGEST",
                new Operator("bob", Operator.Role.VIEWER));

        assertThat(TwoPersonReview.evaluate(r, NOW).verdict())
                .isEqualTo(ReviewVerdict.NOT_AUTHORIZED);
    }

    @Test
    @DisplayName("ADMIN 也不能复核自己发起的单——没有超级管理员可以绕过")
    void adminCannotApproveOwnChange() {
        ReviewOutcome o = TwoPersonReview.evaluate(live("alice", admin("alice")), NOW);

        assertThat(o.verdict()).isEqualTo(ReviewVerdict.SELF_APPROVAL);
        assertThat(o.message()).contains("两个独立的判断");
    }

    @Test
    @DisplayName("发起后被改过：复核依据已失效")
    void staleWhenValueChanged() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", LATER, "SUGGEST", "BOUNDED_AUTO", admin("bob"));

        ReviewOutcome o = TwoPersonReview.evaluate(r, NOW);

        assertThat(o.verdict()).isEqualTo(ReviewVerdict.STALE);
        assertThat(o.message()).contains("SUGGEST").contains("BOUNDED_AUTO");
    }

    @Test
    @DisplayName("发起时无值、现在有值：同样算被改过")
    void staleWhenValueAppeared() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", LATER, null, "SUGGEST", admin("bob"));

        assertThat(TwoPersonReview.evaluate(r, NOW).verdict()).isEqualTo(ReviewVerdict.STALE);
    }

    // ------------------------------------------------------------------ 顺序（承重部分）

    @Test
    @DisplayName("顺序：过期 在 角色不足 之前——死单不必再谈权限")
    void expiryIsCheckedBeforeRole() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", NOW, "SUGGEST", "SUGGEST",
                new Operator("bob", Operator.Role.VIEWER));

        assertThat(TwoPersonReview.evaluate(r, NOW).verdict()).isEqualTo(ReviewVerdict.EXPIRED);
    }

    @Test
    @DisplayName("顺序（安全关键）：角色不足 在 「已被改过」之前，且不得泄露当前值")
    void roleIsCheckedBeforeStalenessAndDoesNotLeakCurrentValue() {
        // 若顺序颠倒，一个 VIEWER 就能靠反复发起复核，
        // 从 STALE 的错误消息里把当前生效值一条条读出来。
        // BACKEND_ONLY 的配置项对前端是 404（连存在性都不泄露），
        // 却会从这条错误消息里漏出值——那比 404 更糟。
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", LATER, "SUGGEST", CURRENT_SECRET,
                new Operator("bob", Operator.Role.VIEWER));

        ReviewOutcome o = TwoPersonReview.evaluate(r, NOW);

        assertThat(o.verdict()).isEqualTo(ReviewVerdict.NOT_AUTHORIZED);
        assertThat(o.message()).as("无权者不得看到当前生效值").doesNotContain(CURRENT_SECRET);
    }

    @Test
    @DisplayName("顺序：不能自审 在 「已被改过」之前，同样不泄露当前值")
    void selfApprovalIsCheckedBeforeStalenessAndDoesNotLeakCurrentValue() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", LATER, "SUGGEST", CURRENT_SECRET,
                admin("alice"));

        ReviewOutcome o = TwoPersonReview.evaluate(r, NOW);

        assertThat(o.verdict()).isEqualTo(ReviewVerdict.SELF_APPROVAL);
        assertThat(o.message()).as("自审者不得看到当前生效值").doesNotContain(CURRENT_SECRET);
    }

    @Test
    @DisplayName("对有权复核的人，STALE 消息必须含当前值——否则他无法判断该不该重新发起")
    void staleMessageDoesContainCurrentValueForAuthorizedReviewer() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", LATER, "SUGGEST", CURRENT_SECRET,
                admin("bob"));

        assertThat(TwoPersonReview.evaluate(r, NOW).message()).contains(CURRENT_SECRET);
    }

    @Test
    @DisplayName("四条拒绝原因互不混淆：过期/无权/自审/已改 是四种不同的处置")
    void allFourRejectionsAreDistinct() {
        assertThat(TwoPersonReview.evaluate(
                new ReviewRequest(SUBJECT, "alice", NOW, "S", "S", admin("bob")), NOW).verdict())
                .isEqualTo(ReviewVerdict.EXPIRED);
        assertThat(TwoPersonReview.evaluate(
                new ReviewRequest(SUBJECT, "alice", LATER, "S", "S",
                        new Operator("bob", Operator.Role.EDITOR)), NOW).verdict())
                .isEqualTo(ReviewVerdict.NOT_AUTHORIZED);
        assertThat(TwoPersonReview.evaluate(
                new ReviewRequest(SUBJECT, "alice", LATER, "S", "S", admin("alice")), NOW).verdict())
                .isEqualTo(ReviewVerdict.SELF_APPROVAL);
        assertThat(TwoPersonReview.evaluate(
                new ReviewRequest(SUBJECT, "alice", LATER, "S", "T", admin("bob")), NOW).verdict())
                .isEqualTo(ReviewVerdict.STALE);
    }

    // ------------------------------------------------------------------ 展示细节与防御

    @Test
    @DisplayName("null 值在提示语里显示为「无」而不是字面量 null")
    void nullShowsAsChineseNone() {
        ReviewRequest r = new ReviewRequest(SUBJECT, "alice", LATER, null, "SUGGEST", admin("bob"));

        assertThat(TwoPersonReview.evaluate(r, NOW).message()).contains("无").doesNotContain("null");
    }

    @Test
    @DisplayName("subjectLabel 缺失不影响判定，只让提示语退化为「该对象」")
    void missingSubjectLabelDegradesGracefully() {
        ReviewRequest r = new ReviewRequest(null, "alice", LATER, "S", "T", admin("bob"));

        ReviewOutcome o = TwoPersonReview.evaluate(r, NOW);

        assertThat(o.verdict()).isEqualTo(ReviewVerdict.STALE);
        assertThat(o.message()).contains("该对象");
    }

    @Test
    @DisplayName("没有发起人的单子直接构造失败——没有发起人就无法判定自审")
    void blankRequesterRejected() {
        assertThatThrownBy(() -> new ReviewRequest(SUBJECT, "  ", LATER, "S", "S", admin("bob")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewRequest(SUBJECT, null, LATER, "S", "S", admin("bob")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("deny() 不能传 ALLOWED——否则「拒绝」会带着通过的语义返回")
    void denyCannotCarryAllowed() {
        assertThatThrownBy(() -> ReviewOutcome.deny(ReviewVerdict.ALLOWED, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Operator 身份不可伪造：无法识别的角色按最低权限处理")
    void unknownRoleDowngradesToViewer() {
        assertThat(Operator.fromHeaders("alice", "SUPERUSER").role()).isEqualTo(Operator.Role.VIEWER);
        assertThat(Operator.fromHeaders("alice", "super-admin").role()).isEqualTo(Operator.Role.VIEWER);
        assertThat(Operator.fromHeaders(null, "ADMIN").isAnonymous()).isTrue();
    }
}
