package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link ApprovalRecord} 的不变量测试。
 *
 * <p><b>为什么要为一个 record 写这么多测试</b>：这张表的保留期是永久的，
 * DDL 注释写明「它是责任归属的唯一凭据」。
 * 审计表填错了是查不准，审批记录填错了是<b>把责任记到错的人头上</b>。
 * 所以每一个字段的形状都要在构造期就钉死，而不是等 INSERT 撞约束——
 * 后者的失败点在数据库里，调用栈早就走远了，
 * 而「谁试图自批」这个安全信号也就丢了。
 */
class ApprovalRecordTest {

    private static final ToolAuditContext CTX =
            new ToolAuditContext("trace-abc", "run-1", "step-2", "alice");

    private static ApprovalRecord pending() {
        return ApprovalRecord.pending("ap-1", CTX, "scale_replicas", RiskLevel.HIGH,
                "alice", "{\"replicas\":8}");
    }

    // ------------------------------------------------------------ PENDING 的形状

    @Test
    @DisplayName("PENDING 记录的审批人和决定时间都必须为空")
    void pendingHasNoApproverOrDecidedAt() {
        ApprovalRecord r = pending();
        assertThat(r.decision()).isEqualTo(ApprovalDecision.PENDING);
        assertThat(r.approver()).isNull();
        assertThat(r.decidedAt()).isNull();
        assertThat(r.decision().isFinal()).isFalse();
        assertThat(r.decision().isApproved()).isFalse();
    }

    @Test
    @DisplayName("PENDING 却带审批人 → 拒绝：还没人做决定")
    void pendingForbidsApprover() {
        assertThatThrownBy(() -> new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH,
                "alice", "{}", ApprovalDecision.PENDING, "bob", null, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING 不应有 approver");
    }

    @Test
    @DisplayName("PENDING 却带决定时间 → 拒绝")
    void pendingForbidsDecidedAt() {
        assertThatThrownBy(() -> new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH,
                "alice", "{}", ApprovalDecision.PENDING, null, null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PENDING 不应有 decidedAt");
    }

    // ------------------------------------------------------------ 不能自批

    @Test
    @DisplayName("审批人 = 申请人 → 拒绝（chk_approval_not_self 在内存里就先拦）")
    void selfApprovalIsRejected() {
        assertThatThrownBy(() -> new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH,
                "alice", "{}", ApprovalDecision.GRANTED, "alice", null,
                Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("审批人不能与申请人相同");
    }

    // ------------------------------------------------------------ 终态的字段要求

    @Test
    @DisplayName("GRANTED 没有审批人 → 拒绝：没有人名的批准等于没有责任人")
    void grantedRequiresApprover() {
        assertThatThrownBy(() -> new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH,
                "alice", "{}", ApprovalDecision.GRANTED, null, null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须有 approver");
    }

    @Test
    @DisplayName("REJECTED 没有审批人 → 拒绝")
    void rejectedRequiresApprover() {
        assertThatThrownBy(() -> new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH,
                "alice", "{}", ApprovalDecision.REJECTED, null, null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须有 approver");
    }

    @Test
    @DisplayName("GRANTED 没有决定时间 → 拒绝")
    void grantedRequiresDecidedAt() {
        assertThatThrownBy(() -> new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH,
                "alice", "{}", ApprovalDecision.GRANTED, "bob", null, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须有 decidedAt");
    }

    @Test
    @DisplayName("TIMED_OUT 却带审批人 → 拒绝：没有人做过这个决定，填人名等于伪造责任归属")
    void timedOutForbidsApprover() {
        assertThatThrownBy(() -> new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH,
                "alice", "{}", ApprovalDecision.TIMED_OUT, "bob", null, Instant.now(), Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TIMED_OUT 不应有 approver");
    }

    @Test
    @DisplayName("TIMED_OUT 没有决定时间 → 拒绝：超时时刻就是升级通知的触发点")
    void timedOutRequiresDecidedAt() {
        assertThatThrownBy(() -> new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH,
                "alice", "{}", ApprovalDecision.TIMED_OUT, null, null, Instant.now(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TIMED_OUT 必须有 decidedAt");
    }

    // ------------------------------------------------------------ 基本校验

    @Test
    @DisplayName("申请人空白 → 拒绝：requester 是 NOT NULL")
    void blankRequesterIsRejected() {
        assertThatThrownBy(() -> ApprovalRecord.pending("ap-1", CTX, "t", RiskLevel.HIGH,
                "   ", "{}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requester 不能为空");
    }

    @Test
    @DisplayName("快照为 null → 拒绝：没有快照就无法证明审批人看到了什么")
    void nullArgsSnapshotIsRejected() {
        assertThatThrownBy(() -> ApprovalRecord.pending("ap-1", CTX, "t", RiskLevel.HIGH,
                "alice", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("argsSnapshot 不能为 null");
    }

    @Test
    @DisplayName("决定时间早于请求时间 → 拒绝")
    void decidedAtBeforeRequestedAtIsRejected() {
        Instant now = Instant.now();
        assertThatThrownBy(() -> new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH,
                "alice", "{}", ApprovalDecision.GRANTED, "bob", null,
                now, now.minusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("decidedAt 不能早于 requestedAt");
    }

    @Test
    @DisplayName("备注会被修剪，超长会被截断到 500")
    void commentIsTrimmedAndTruncated() {
        ApprovalRecord r = new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH, "alice", "{}",
                ApprovalDecision.GRANTED, "bob", "  " + "x".repeat(600) + "  ",
                Instant.now(), Instant.now());
        assertThat(r.comment()).hasSize(ApprovalRecord.MAX_COMMENT);
        assertThat(r.comment()).doesNotContain(" ");

        ApprovalRecord blank = new ApprovalRecord("ap-1", CTX, "t", RiskLevel.HIGH, "alice", "{}",
                ApprovalDecision.GRANTED, "bob", "   ", Instant.now(), Instant.now());
        assertThat(blank.comment()).as("全空白备注归一成 null").isNull();
    }

    // ------------------------------------------------------------ 状态迁移

    @Test
    @DisplayName("decided 返回新对象而非原地修改，字段原样带过去")
    void decidedReturnsNewRecord() {
        ApprovalRecord p = pending();
        ApprovalRecord g = p.decided(ApprovalDecision.GRANTED, "bob", "扩容合理");

        assertThat(g).isNotSameAs(p);
        assertThat(p.decision()).as("原记录不变").isEqualTo(ApprovalDecision.PENDING);
        assertThat(g.decision()).isEqualTo(ApprovalDecision.GRANTED);
        assertThat(g.approver()).isEqualTo("bob");
        assertThat(g.comment()).isEqualTo("扩容合理");
        assertThat(g.decidedAt()).isNotNull();
        assertThat(g.requestedAt()).isEqualTo(p.requestedAt());
        assertThat(g.id()).isEqualTo(p.id());
        assertThat(g.argsSnapshot()).isEqualTo(p.argsSnapshot());
    }

    @Test
    @DisplayName("decided 传 PENDING → 拒绝：结论必须是终态")
    void decidedRejectsPendingOutcome() {
        assertThatThrownBy(() -> pending().decided(ApprovalDecision.PENDING, "bob", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("结论必须是终态");
    }

    // ------------------------------------------------------------ 日志安全

    @Test
    @DisplayName("toString 不打印参数快照")
    void toStringOmitsArgsSnapshot() {
        ApprovalRecord r = ApprovalRecord.pending("ap-1", CTX, "scale_replicas",
                RiskLevel.HIGH, "alice", "{\"secret\":\"SUPER-SECRET-VALUE\"}");
        String s = r.toString();
        assertThat(s).doesNotContain("SUPER-SECRET-VALUE");
        assertThat(s).contains("ap-1").contains("PENDING");
    }
}
