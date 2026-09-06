package com.oncall.agent.query;

import java.util.Optional;

/**
 * 意图闭集（《查询理解与知识表示设计》§1.2）。
 *
 * <p><b>用闭集而不是开放集</b>：OnCall 的意图是有限且可枚举的。
 * 开放集会让"路由"变成一个需要不断补分支的开关，
 * 而闭集让"模型返回了集合外的标签"变成一个<b>可检测的错误</b>——
 * 后者才有确定的处理方式（拒绝，而不是猜）。
 *
 * <p><b>{@code OUT_OF_SCOPE} 是这里最重要的一个值。</b>
 * 拒答率是双侧指标（目标 5–25%）：太低说明什么都答（幻觉风险高），
 * 太高说明系统没用。一个只会"尽力回答"的系统在运维场景是危险的。
 */
public enum Intent {

    /** 解释这条告警。只读，可自动。 */
    EXPLAIN_ALERT,

    /** 查历史相似故障。只读，可自动。 */
    QUERY_HISTORY,

    /** 发起排查。Plan-Execute-Replan，异步 202，受放权等级约束。 */
    DIAGNOSE,

    /** 请求执行操作。<b>强制走审批闸门</b>，受放权等级 + kill switch 约束。 */
    EXECUTE,

    /** 改配置。<b>必须转出对话通道</b>，走双人复核。 */
    CONFIGURE,

    /** 超出能力范围。拒绝。 */
    OUT_OF_SCOPE,

    /** 闲聊。极简回应或拒绝。 */
    CHITCHAT;

    /**
     * 解析模型返回的标签。
     *
     * <p>返回 {@code Optional.empty()} 而不是抛异常或回退到某个默认值：
     * <b>调用方需要知道"模型说了集合外的话"这件事本身</b>，
     * 那是调整 prompt 的输入。在这里悄悄映射成 {@code OUT_OF_SCOPE}
     * 会让这个信号永久消失。
     */
    public static Optional<Intent> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String normalized = raw.trim().toUpperCase(java.util.Locale.ROOT);
        for (Intent i : values()) {
            if (i.name().equals(normalized)) {
                return Optional.of(i);
            }
        }
        return Optional.empty();
    }

    /**
     * 这个意图是否意味着"改变世界的状态"。
     *
     * <p>只有 {@code EXECUTE} 与 {@code CONFIGURE}：
     * <ul>
     *   <li>{@code DIAGNOSE} 本身是只读的——它产出一个<b>计划</b>，
     *       计划里的写操作在各自那一步再受闸门约束。
     *       把 DIAGNOSE 也算成写意图会让"能不能自动排查"和
     *       "能不能自动执行"两个问题混成一个。</li>
     *   <li>{@code CONFIGURE} 虽然走的是配置 API 而不是工具网关，
     *       但它改的是系统行为，且《测试与交付保障体系》§4.2
     *       把行为类配置变更列为强制灰度。</li>
     * </ul>
     */
    public boolean isWriteIntent() {
        return this == EXECUTE || this == CONFIGURE;
    }
}
