package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;

import java.time.Instant;
import java.util.Objects;

/**
 * 一条审批记录 —— 与 {@code approval_record} 的一行一一对应。
 *
 * <p><b>这张表的保留期是永久的</b>，DDL 注释写的是
 * 「它是责任归属的唯一凭据」。所以它比 {@code tool_audit_log}（180 天）
 * 更不能容忍字段造假：审计表填错了是查不准，
 * 审批记录填错了是<b>把责任记到错的人头上</b>。
 *
 * <p><b>本类在构造期强制数据库的那些约束</b>，理由与
 * {@link ToolAuditEvent} 相同：让「填不满 / 填错」在 JVM 里就报错，
 * 而不是等到 INSERT 撞约束——后者的失败点在数据库里，调用栈早就走远了。
 */
public record ApprovalRecord(
        String id,
        ToolAuditContext context,
        String toolName,
        RiskLevel riskLevel,
        String requester,
        String argsSnapshot,
        ApprovalDecision decision,
        String approver,
        String comment,
        Instant requestedAt,
        Instant decidedAt) {

    /** 与 DDL 列宽一致。 */
    public static final int MAX_ID = 64;
    public static final int MAX_TOOL_NAME = 191;
    public static final int MAX_PERSON = 64;
    public static final int MAX_COMMENT = 500;

    public ApprovalRecord {
        Objects.requireNonNull(context, "context：审批记录没有 trace 就无法与 agent_run 关联");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(requestedAt, "requestedAt");

        id = require(id, "id", MAX_ID);
        toolName = require(toolName, "toolName", MAX_TOOL_NAME);
        requester = require(requester, "requester", MAX_PERSON);
        approver = optional(approver, "approver", MAX_PERSON);

        if (argsSnapshot == null) {
            throw new IllegalArgumentException(
                    "argsSnapshot 不能为 null：DDL 注释写明「必须是夹紧后且脱敏后的值，"
                            + "否则这次审批无效」——没有快照就无法证明审批人看到了什么");
        }

        if (comment != null) {
            comment = comment.isBlank() ? null : comment.trim();
            if (comment != null && comment.length() > MAX_COMMENT) {
                comment = comment.substring(0, MAX_COMMENT);
            }
        }

        // ── chk_approval_not_self：不能自批 ────────────────────────
        // 数据库有这个 CHECK，但只靠数据库不够：
        // 撞约束时异常从 JDBC 层抛出来，而「谁试图自批」这个安全信号就丢了。
        if (approver != null && approver.equals(requester)) {
            throw new IllegalArgumentException(
                    "审批人不能与申请人相同（chk_approval_not_self）：" + approver);
        }

        // ── 各结论的字段一致性 ────────────────────────────────────
        switch (decision) {
            case PENDING -> {
                if (approver != null) {
                    throw new IllegalArgumentException("PENDING 不应有 approver：还没人做决定");
                }
                if (decidedAt != null) {
                    throw new IllegalArgumentException("PENDING 不应有 decidedAt：还没决定");
                }
            }
            case GRANTED, REJECTED -> {
                if (approver == null) {
                    throw new IllegalArgumentException(
                            decision + " 必须有 approver：这是责任归属的唯一凭据，"
                                    + "没有人名的批准/拒绝等于没有责任人");
                }
                if (decidedAt == null) {
                    throw new IllegalArgumentException(decision + " 必须有 decidedAt");
                }
            }
            case TIMED_OUT -> {
                if (approver != null) {
                    throw new IllegalArgumentException(
                            "TIMED_OUT 不应有 approver：没有人做过这个决定，"
                                    + "填人名等于伪造责任归属");
                }
                if (decidedAt == null) {
                    throw new IllegalArgumentException(
                            "TIMED_OUT 必须有 decidedAt：超时时刻就是升级通知的触发点");
                }
            }
        }
        if (decidedAt != null && decidedAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException(
                    "decidedAt 不能早于 requestedAt：" + decidedAt + " < " + requestedAt);
        }
    }

    /** 提交一条待审批记录。 */
    public static ApprovalRecord pending(String id, ToolAuditContext context, String toolName,
                                         RiskLevel risk, String requester, String argsSnapshot) {
        return new ApprovalRecord(id, context, toolName, risk, requester, argsSnapshot,
                ApprovalDecision.PENDING, null, null, Instant.now(), null);
    }

    /**
     * 产出一条已决定的记录。
     *
     * <p>刻意返回新对象而不是原地改：审批记录是责任凭据，
     * 可变对象会让「什么时候变成 GRANTED 的」这件事无法追溯。
     */
    public ApprovalRecord decided(ApprovalDecision outcome, String who, String note) {
        if (outcome == null || !outcome.isFinal()) {
            throw new IllegalArgumentException("结论必须是终态，收到：" + outcome);
        }
        return new ApprovalRecord(id, context, toolName, riskLevel, requester, argsSnapshot,
                outcome, who, note, requestedAt, Instant.now());
    }

    private static String require(String v, String field, int max) {
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
        String t = v.trim();
        if (t.length() > max) {
            throw new IllegalArgumentException(field + " 超过 " + max + " 字符：" + t.length());
        }
        return t;
    }

    private static String optional(String v, String field, int max) {
        if (v == null) {
            return null;
        }
        String t = v.trim();
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > max) {
            throw new IllegalArgumentException(field + " 超过 " + max + " 字符：" + t.length());
        }
        return t;
    }

    @Override
    public String toString() {
        // 不打印 argsSnapshot：toString 会进普通日志，
        // 而普通日志没有这张表的脱敏与永久保留约束。
        return "ApprovalRecord[" + id + " " + decision + " " + toolName
                + " requester=" + requester + " approver=" + approver + "]";
    }
}
