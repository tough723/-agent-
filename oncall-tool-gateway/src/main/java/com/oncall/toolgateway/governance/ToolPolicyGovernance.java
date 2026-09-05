package com.oncall.toolgateway.governance;

import com.oncall.domain.governance.Operator;
import com.oncall.domain.governance.ReviewOutcome;
import com.oncall.domain.governance.ReviewRequest;
import com.oncall.domain.governance.ReviewVerdict;
import com.oncall.domain.governance.TwoPersonReview;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.toolgateway.ToolPolicyEngine;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 工具白名单的变更治理 —— 补上「谁在什么时候放行了什么」这一半。
 *
 * <h2>要解决的缺口</h2>
 *
 * {@code ToolPolicy} 是整个安全模型的事实来源：加一条 MCP 工具策略
 * 就等于放行一个远端工具。但在此之前，加策略就是调一次
 * {@code ToolPolicyEngine.register()} —— 没有第二人复核，
 * 也没有可查记录。配置治理那一套只覆盖 {@code OnCallConfigRegistry}。
 *
 * <p>这相当于给"连接"（{@code mcp.allowed-servers}）装了锁，
 * 而真正承重的"授权"是敞开的。
 *
 * <h2>与配置治理共享什么、不共享什么</h2>
 *
 * <table border="1">
 *   <caption>接缝</caption>
 *   <tr><th>共享</th><td><b>判定规则</b>：{@link TwoPersonReview}（领域层，只有一份）</td></tr>
 *   <tr><th>不共享</th><td><b>单据形状</b>：配置侧载荷是两个 String，
 *       工具侧是一个 {@link ToolPolicy} 加变更类型</td></tr>
 *   <tr><th>不共享</th><td><b>哪些变更要复核</b>：配置侧是硬编码的 5 个键，
 *       工具侧是 {@link PolicyRiskDelta} 算出来的风险方向</td></tr>
 * </table>
 *
 * 规则分叉才会出事故，单据形状分叉不会——所以只共享规则。
 *
 * <h2>刻意不做的事</h2>
 *
 * <ul>
 *   <li><b>不把"是否需要复核"做成配置项</b>。理由与
 *       {@code ConfigAccessPolicy.HIGH_RISK_KEYS} 硬编码一致：
 *       能放宽工具策略的人，同样可以先把"放宽不需要复核"这条开关打开。
 *       守卫自己的东西不能交给被守卫的对象。</li>
 *   <li><b>不提供绕过复核的入口</b>。没有 force 参数。</li>
 * </ul>
 *
 * <h2>已知的、还没堵上的一半</h2>
 *
 * {@code ToolPolicyEngine.register()} / {@code revoke()} <b>仍然是 public</b>。
 * 也就是说这个治理层是"应当走的路"，还不是"唯一能走的路"——
 * 拿到引擎引用的代码仍然可以直接改白名单。要真正封死，
 * 得把这两个方法降为包级可见，而 {@code McpToolRegistrarTest} 在
 * {@code com.oncall.toolgateway.mcp} 子包里用了它们，需要一并调整。
 * 这一条已登记为后续项，不在本轮夹带：<b>能说清"还差什么"比假装已经封死重要</b>。
 */
public class ToolPolicyGovernance {

    /** 与配置侧一致：15 分钟。理由见 {@link ToolPolicyChangeTicket}。 */
    public static final long TICKET_TTL_MILLIS = 15 * 60 * 1000L;

    private final ToolPolicyEngine engine;
    private final ToolPolicyChangeTicketStore tickets;
    private final ToolPolicyChangeAudit audit;
    private final LongSupplier clock;

    public ToolPolicyGovernance(ToolPolicyEngine engine,
                                ToolPolicyChangeTicketStore tickets,
                                ToolPolicyChangeAudit audit) {
        this(engine, tickets, audit, System::currentTimeMillis);
    }

    /** 可注入时钟，用于确定性地测试过期。 */
    public ToolPolicyGovernance(ToolPolicyEngine engine,
                                ToolPolicyChangeTicketStore tickets,
                                ToolPolicyChangeAudit audit,
                                LongSupplier clock) {
        this.engine = Objects.requireNonNull(engine, "engine");
        this.tickets = Objects.requireNonNull(tickets, "tickets");
        this.audit = Objects.requireNonNull(audit, "audit");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    // ------------------------------------------------------------------ 发起

    /**
     * 发起一次变更。
     *
     * <p>两种结果：
     * <ul>
     *   <li>方向为<b>收紧</b> ⇒ 立即生效，审计记 {@code APPLIED_DIRECTLY}；</li>
     *   <li>方向为<b>放宽</b> ⇒ 生成待复核单，<b>不生效</b>，审计记 {@code PROPOSED}。</li>
     * </ul>
     */
    public Proposal propose(ToolPolicyChange change, Operator requester, String reason) {
        Objects.requireNonNull(change, "change");
        Objects.requireNonNull(requester, "requester");
        if (requester.role() == Operator.Role.VIEWER) {
            throw GovernanceException.badRequest("VIEWER 不能发起工具策略变更");
        }

        ToolPolicy current = engine.find(change.toolName()).orElse(null);
        validateKind(change, current);
        PolicyRiskDelta delta = PolicyRiskDelta.between(current, change.proposed());

        ToolPolicyChangeTicket ticket = new ToolPolicyChangeTicket(
                UUID.randomUUID().toString(), change, requester.principal(),
                reason == null ? "" : reason.trim(),
                ToolPolicyChangeTicket.signatureOf(current),
                clock.getAsLong(), clock.getAsLong() + TICKET_TTL_MILLIS);

        if (!delta.widens()) {
            apply(change);
            audit.recordApplied(ticket, requester.principal(),
                    ToolPolicyChangeAudit.Outcome.APPLIED_DIRECTLY);
            return new Proposal(true, null, delta);
        }

        tickets.put(ticket);
        audit.recordProposed(ticket);
        return new Proposal(false, ticket, delta);
    }

    // ------------------------------------------------------------------ 复核

    /**
     * 复核通过一张待复核单。
     *
     * <p>判定本身委托 {@link TwoPersonReview}（领域层），
     * 与配置侧用的是<b>同一份</b>规则。这里只做：取单、算当前签名、
     * 映射失败、真正落地。
     */
    public ToolPolicyChangeTicket confirm(String ticketId, Operator reviewer, String reviewNote) {
        Objects.requireNonNull(reviewer, "reviewer");
        ToolPolicyChangeTicket ticket = requireTicket(ticketId);

        ToolPolicy current = engine.find(ticket.change().toolName()).orElse(null);
        ReviewOutcome outcome = TwoPersonReview.evaluate(new ReviewRequest(
                ticket.change().describe(),
                ticket.requester(),
                ticket.expiresAtMillis(),
                ticket.signatureAtProposal(),
                ToolPolicyChangeTicket.signatureOf(current),
                reviewer), clock.getAsLong());

        if (!outcome.allowed()) {
            // 过期与失效的单子已经没意义了，删掉；无权与自审不删——
            // 那张单子本身是好的，换个人就该能复核。
            if (outcome.verdict() == ReviewVerdict.EXPIRED
                    || outcome.verdict() == ReviewVerdict.STALE) {
                tickets.remove(ticketId);
            }
            audit.recordRejected(ticket, reviewer.principal(), outcome.message());
            throw GovernanceException.fromVerdict(outcome.verdict(), outcome.message());
        }

        tickets.remove(ticketId);
        apply(ticket.change());
        audit.recordApplied(ticket, reviewer.principal(),
                ToolPolicyChangeAudit.Outcome.APPLIED_AFTER_REVIEW);
        return ticket;
    }

    /** 驳回。发起人自己或 ADMIN 都可以。 */
    public void reject(String ticketId, Operator operator, String note) {
        Objects.requireNonNull(operator, "operator");
        ToolPolicyChangeTicket ticket = requireTicket(ticketId);
        boolean isAdmin = operator.role() == Operator.Role.ADMIN;
        if (!isAdmin && !ticket.requester().equals(operator.principal())) {
            throw GovernanceException.badRequest("只有复核人或发起人可以驳回");
        }
        tickets.remove(ticketId);
        audit.recordRejected(ticket, operator.principal(),
                (note == null || note.isBlank()) ? "驳回" : note.trim());
    }

    /** 当前未决单子，按发起时间排序。 */
    public List<ToolPolicyChangeTicket> openTickets() {
        return tickets.open();
    }

    /** 预演一次变更的风险方向，不落地。给界面在提交前提示"这需要两人"。 */
    public PolicyRiskDelta preview(ToolPolicyChange change) {
        ToolPolicy current = engine.find(change.toolName()).orElse(null);
        return PolicyRiskDelta.between(current, change.proposed());
    }

    // ------------------------------------------------------------------ 内部

    private ToolPolicyChangeTicket requireTicket(String ticketId) {
        return tickets.find(ticketId)
                .orElseThrow(() -> GovernanceException.notFound(ticketId));
    }

    /**
     * 变更类型必须与实际状态对得上。
     *
     * <p>不挡的话后果很坏：对一个已存在的工具发 GRANT，
     * 界面上写的是"新增"，实际做的是"覆盖"——审计里留下的是发起人以为自己在做的事。
     */
    private static void validateKind(ToolPolicyChange change, ToolPolicy current) {
        switch (change.kind()) {
            case GRANT -> {
                if (current != null) {
                    throw GovernanceException.badRequest(
                            "工具 " + change.toolName() + " 已在白名单里，请用 UPDATE");
                }
            }
            case UPDATE -> {
                if (current == null) {
                    throw GovernanceException.badRequest(
                            "工具 " + change.toolName() + " 不在白名单里，请用 GRANT");
                }
            }
            case REVOKE -> {
                // 撤销一条不存在的策略是无害的空操作，刻意不报错：
                // "确保它不在白名单里"是一个合理的幂等诉求。
            }
        }
    }

    private void apply(ToolPolicyChange change) {
        if (change.kind() == ToolPolicyChange.Kind.REVOKE) {
            engine.revoke(change.toolName());
        } else {
            engine.register(change.proposed());
        }
    }

    /**
     * 发起结果。
     *
     * @param appliedDirectly 是否已直接生效（方向为收紧时为 true）
     * @param ticket          待复核单；直接生效时为 {@code null}
     * @param delta           风险方向与理由，界面要展示给发起人看
     */
    public record Proposal(boolean appliedDirectly, ToolPolicyChangeTicket ticket,
                           PolicyRiskDelta delta) {
    }
}
