package com.oncall.domain.ticket;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 工单状态机（State 模式）。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>显式转移表</b>：不在表里的转移一律抛 {@link IllegalTransitionException}。
 *       原方案只画了状态机图，没有转移表和校验，代码里很容易出现 RESOLVED → INVESTIGATING 这类脏转移。</li>
 *   <li><b>每个等待人工的状态都有超时出口</b>：原方案全文检索"超时"命中 0 次，
 *       而 PENDING_APPROVAL 一旦审批人不在线就会永久卡死。</li>
 *   <li><b>纯函数</b>：本类不碰数据库。持久化时请用乐观锁
 *       （{@code UPDATE ... SET status=:to WHERE id=:id AND status=:from}），
 *       防止并发事件造成丢更新。</li>
 * </ul>
 */
public final class TicketStateMachine {

    private record Transition(TicketStatus from, TicketEvent event) {}

    private static final Map<Transition, TicketStatus> TABLE = Map.ofEntries(
        Map.entry(new Transition(TicketStatus.NEW,              TicketEvent.ACK),              TicketStatus.ACK),
        Map.entry(new Transition(TicketStatus.ACK,              TicketEvent.START_AI),         TicketStatus.INVESTIGATING),
        Map.entry(new Transition(TicketStatus.INVESTIGATING,    TicketEvent.ROOT_CAUSE_FOUND), TicketStatus.DIAGNOSED),
        Map.entry(new Transition(TicketStatus.INVESTIGATING,    TicketEvent.CANNOT_DIAGNOSE),  TicketStatus.ESCALATED),
        Map.entry(new Transition(TicketStatus.DIAGNOSED,        TicketEvent.PROPOSE_ACTION),   TicketStatus.PENDING_APPROVAL),
        Map.entry(new Transition(TicketStatus.PENDING_APPROVAL, TicketEvent.APPROVED),         TicketStatus.EXECUTING),
        Map.entry(new Transition(TicketStatus.PENDING_APPROVAL, TicketEvent.REJECTED),         TicketStatus.MANUAL_HANDLING),
        Map.entry(new Transition(TicketStatus.PENDING_APPROVAL, TicketEvent.APPROVAL_TIMEOUT), TicketStatus.ESCALATED),
        Map.entry(new Transition(TicketStatus.EXECUTING,        TicketEvent.EXEC_SUCCESS),     TicketStatus.RESOLVED),
        Map.entry(new Transition(TicketStatus.EXECUTING,        TicketEvent.EXEC_FAILURE),     TicketStatus.EXEC_FAILED),
        Map.entry(new Transition(TicketStatus.EXECUTING,        TicketEvent.EXEC_TIMEOUT),     TicketStatus.EXEC_FAILED),
        Map.entry(new Transition(TicketStatus.EXEC_FAILED,      TicketEvent.COMPENSATED),      TicketStatus.MANUAL_HANDLING),
        Map.entry(new Transition(TicketStatus.ESCALATED,        TicketEvent.HUMAN_TAKEOVER),   TicketStatus.MANUAL_HANDLING),
        Map.entry(new Transition(TicketStatus.RESOLVED,         TicketEvent.START_REVIEW),     TicketStatus.REVIEW),
        Map.entry(new Transition(TicketStatus.MANUAL_HANDLING,  TicketEvent.START_REVIEW),     TicketStatus.REVIEW),
        Map.entry(new Transition(TicketStatus.EXEC_FAILED,      TicketEvent.START_REVIEW),     TicketStatus.REVIEW),
        Map.entry(new Transition(TicketStatus.REVIEW,           TicketEvent.KB_INDEXED),       TicketStatus.KNOWLEDGE_INDEXED)
    );

    /** 各状态的超时上限；到点由扫描器触发对应事件。 */
    private static final Map<TicketStatus, Duration> TIMEOUTS = Map.of(
        TicketStatus.PENDING_APPROVAL, Duration.ofMinutes(15),
        TicketStatus.EXECUTING,        Duration.ofMinutes(10),
        TicketStatus.INVESTIGATING,    Duration.ofMinutes(30)
    );

    private TicketStateMachine() {}

    /**
     * 计算目标状态。
     *
     * @throws IllegalTransitionException 若该 (from, event) 组合不在转移表内
     */
    public static TicketStatus next(TicketStatus from, TicketEvent event) {
        TicketStatus to = TABLE.get(new Transition(from, event));
        if (to == null) {
            throw new IllegalTransitionException(from, event);
        }
        return to;
    }

    /** 不抛异常的探测版本，供 UI / 校验器使用。 */
    public static Optional<TicketStatus> tryNext(TicketStatus from, TicketEvent event) {
        return Optional.ofNullable(TABLE.get(new Transition(from, event)));
    }

    public static boolean canFire(TicketStatus from, TicketEvent event) {
        return TABLE.containsKey(new Transition(from, event));
    }

    /** 该状态是否有超时出口。等待人工却没有超时 = 设计缺陷。 */
    public static Optional<Duration> timeoutOf(TicketStatus status) {
        return Optional.ofNullable(TIMEOUTS.get(status));
    }
}
