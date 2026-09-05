package com.oncall.domain.tool;

/** 工具被策略引擎拒绝。默认拒绝（default-deny）的落地形式。 */
public class ToolDeniedException extends RuntimeException {

    private final String toolName;
    private final String reason;

    public ToolDeniedException(String toolName, String reason) {
        super("tool denied: " + toolName + " (" + reason + ")");
        this.toolName = toolName;
        this.reason = reason;
    }

    public String toolName() {
        return toolName;
    }

    public String reason() {
        return reason;
    }
}
