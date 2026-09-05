package com.oncall.tooladmin;

import com.oncall.toolgateway.governance.ToolPolicyChangeTicket;

/** 待复核单的前端视图。 */
public record ToolPolicyTicketView(
        String id,
        String toolName,
        String kind,
        String requester,
        String reason,
        String description,
        long createdAtMillis,
        long expiresAtMillis
) {

    public static ToolPolicyTicketView of(ToolPolicyChangeTicket t) {
        return new ToolPolicyTicketView(
                t.id(), t.change().toolName(), t.change().kind().name(),
                t.requester(), t.reason(), t.change().describe(),
                t.createdAtMillis(), t.expiresAtMillis());
    }
}
