package com.oncall.toolgateway.governance;

import com.oncall.domain.tool.ToolPolicy;

/**
 * 一次拟议的工具策略变更。
 *
 * @param kind     变更类型
 * @param toolName 目标工具名。MCP 工具用 {@code mcp:<server>:<tool>}
 * @param proposed 拟生效的策略；{@code REVOKE} 时为 {@code null}
 */
public record ToolPolicyChange(Kind kind, String toolName, ToolPolicy proposed) {

    public ToolPolicyChange {
        if (toolName == null || toolName.isBlank()) {
            throw new IllegalArgumentException("toolName 不能为空");
        }
        if (kind == Kind.REVOKE) {
            if (proposed != null) {
                throw new IllegalArgumentException("REVOKE 不应携带策略");
            }
        } else if (proposed == null) {
            throw new IllegalArgumentException(kind + " 必须携带策略");
        } else if (!toolName.equals(proposed.toolName())) {
            // 不挡这一条的话，"改 mcp:a:x" 的单子会静默改到 mcp:b:y 上，
            // 而审计里记的是发起人填的那个名字。
            throw new IllegalArgumentException("toolName 与策略里的工具名不一致："
                    + toolName + " vs " + proposed.toolName());
        }
    }

    public static ToolPolicyChange grant(ToolPolicy policy) {
        return new ToolPolicyChange(Kind.GRANT, policy.toolName(), policy);
    }

    public static ToolPolicyChange update(ToolPolicy policy) {
        return new ToolPolicyChange(Kind.UPDATE, policy.toolName(), policy);
    }

    public static ToolPolicyChange revoke(String toolName) {
        return new ToolPolicyChange(Kind.REVOKE, toolName, null);
    }

    /** 用于 {@code ReviewRequest.subjectLabel} 与审计展示。 */
    public String describe() {
        return switch (kind) {
            case GRANT -> "新增工具策略 " + toolName + "（" + proposed.source() + " / " + proposed.risk() + "）";
            case UPDATE -> "修改工具策略 " + toolName;
            case REVOKE -> "撤销工具策略 " + toolName;
        };
    }

    public enum Kind {
        /** 把一个新工具加进白名单（此前默认拒绝）。 */
        GRANT,
        /** 修改已有工具的策略。 */
        UPDATE,
        /** 从白名单移除，回到默认拒绝。 */
        REVOKE
    }
}
