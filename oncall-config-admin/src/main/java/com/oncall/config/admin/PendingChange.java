package com.oncall.config.admin;

/**
 * 一个已发起、等待第二人复核的高危配置变更。
 *
 * <p><b>为什么要有有效期</b>：双人复核的意义在于"两个独立的人在同一时间点上
 * 都认为这个改动是对的"。如果一个待复核变更能挂三天，那第二天复核的人
 * 面对的是完全不同的系统状态（告警量、值班人、故障情况都变了），
 * 他的"同意"就不再是对当前状态的判断。所以过期即失效，必须重新发起。
 *
 * <p>另外，待复核变更<b>不写库</b>——只有复核通过才真正写入并审计。
 * 否则审计日志里会出现大量"发起了但没生效"的记录，把真正的变更淹没。
 *
 * @param id             变更单号，由服务端生成
 * @param key            配置键
 * @param newValue       拟写入的值；为 {@code null} 表示拟恢复默认值
 * @param requester      发起人
 * @param reason         发起理由，必填
 * @param oldValue       发起时的生效值，<b>快照</b>——用于复核时判断"期间有没有被别人改过"
 * @param createdAtMillis 发起时间
 * @param expiresAtMillis 过期时间
 */
public record PendingChange(
        String id,
        String key,
        String newValue,
        String requester,
        String reason,
        String oldValue,
        long createdAtMillis,
        long expiresAtMillis
) {

    public boolean isReset() {
        return newValue == null;
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    /**
     * 发起之后、复核之前，这个键是否被别人改过。
     *
     * <p>如果改过，复核方看到的"当前值"就和发起人当时看到的不一样，
     * 这次复核的判断依据已经失效，应当拒绝并要求重新发起。
     */
    public boolean staleAgainst(String currentEffectiveValue) {
        if (oldValue == null) {
            return currentEffectiveValue != null;
        }
        return !oldValue.equals(currentEffectiveValue);
    }
}
