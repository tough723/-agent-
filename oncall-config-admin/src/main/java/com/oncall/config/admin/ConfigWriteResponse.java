package com.oncall.config.admin;

/**
 * 配置写入的响应。
 *
 * <p>一个 DTO 覆盖两种结果，避免前端要判断两种响应结构：
 * <ul>
 *   <li>{@code applied=true}（HTTP 200）：已生效</li>
 *   <li>{@code applied=false}（HTTP 202）：高危项，已生成待复核单，
 *       需要另一个人调 {@code POST /api/config/pending/{id}/confirm}</li>
 * </ul>
 *
 * @param key             配置键
 * @param applied         是否已生效
 * @param oldValue        变更前的生效值
 * @param newValue        变更后的生效值；未生效时为拟写入的值
 * @param pendingChangeId 待复核单号；已生效时为 {@code null}
 * @param requiresApproval 该键是否为高危项——前端据此在表单上提示"保存后需他人复核"
 * @param message         给前端展示的说明
 */
public record ConfigWriteResponse(
        String key,
        boolean applied,
        String oldValue,
        String newValue,
        String pendingChangeId,
        boolean requiresApproval,
        String message
) {

    public static ConfigWriteResponse applied(String key, String oldValue, String newValue,
                                              boolean requiresApproval) {
        return new ConfigWriteResponse(key, true, oldValue, newValue, null,
                requiresApproval, "已生效");
    }

    public static ConfigWriteResponse pending(PendingChange change, boolean requiresApproval) {
        return new ConfigWriteResponse(change.key(), false, change.oldValue(), change.newValue(),
                change.id(), requiresApproval,
                "该配置项为高危项，已生成待复核单，需要另一位具备复核权限的人确认后才会生效");
    }

    /** 值没变化时的响应：不写库、不审计，但仍要告诉前端"没变化"而不是静默成功。 */
    public static ConfigWriteResponse unchanged(String key, String value, boolean requiresApproval) {
        return new ConfigWriteResponse(key, true, value, value, null, requiresApproval,
                "值与当前生效值相同，未产生变更");
    }
}
