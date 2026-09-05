package com.oncall.tooladmin;

import com.oncall.domain.tool.ToolPolicy;

/**
 * 白名单条目的前端视图。
 *
 * <p><b>刻意不含 {@code argsJsonSchema} 的内容</b>，只给 {@code hasArgsSchema}：
 * 它可能是一段很长的 JSON，列表页不需要；要看内容去详情页。
 * 更重要的是——参数 schema 会暴露工具接受什么参数，
 * 那属于工具的接口细节，不是白名单概览该摊开的东西。
 */
public record ToolPolicyView(
        String toolName,
        String source,
        String risk,
        boolean requiresApproval,
        boolean requiresDualApproval,
        long approvalTimeoutSeconds,
        boolean hasArgsSchema
) {

    public static ToolPolicyView of(ToolPolicy p) {
        return new ToolPolicyView(
                p.toolName(), p.source().name(), p.risk().name(),
                p.requiresApproval(), p.requiresDualApproval(),
                p.approvalTimeout() == null ? 0L : p.approvalTimeout().toSeconds(),
                p.argsJsonSchema() != null);
    }
}
