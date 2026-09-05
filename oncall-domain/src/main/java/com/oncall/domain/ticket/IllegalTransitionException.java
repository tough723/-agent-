package com.oncall.domain.ticket;

/** 非法状态转移。抛出即说明有代码在制造脏状态。 */
public class IllegalTransitionException extends RuntimeException {

    private final TicketStatus from;
    private final TicketEvent event;

    public IllegalTransitionException(TicketStatus from, TicketEvent event) {
        super("illegal transition: " + from + " --" + event + "--> ?");
        this.from = from;
        this.event = event;
    }

    public TicketStatus from() {
        return from;
    }

    public TicketEvent event() {
        return event;
    }
}
