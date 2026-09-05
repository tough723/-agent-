package com.oncall.tooladmin;

import java.util.List;

/**
 * 变更请求的结果。
 *
 * @param toolName       目标工具
 * @param applied        是否已生效（方向为收紧时直接生效）
 * @param requiresReview 是否需要第二人复核
 * @param ticketId       待复核单号；直接生效时为 {@code null}
 * @param reasons        风险方向的判定理由，<b>必须回给前端</b>：
 *                       发起人要知道自己这次改动为什么需要两个人，
 *                       否则界面上只会看到一个莫名其妙的"已提交待复核"
 */
public record ToolPolicyWriteResponse(
        String toolName,
        boolean applied,
        boolean requiresReview,
        String ticketId,
        List<String> reasons
) {
}
