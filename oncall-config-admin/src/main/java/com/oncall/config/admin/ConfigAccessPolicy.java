package com.oncall.config.admin;

import com.oncall.config.ConfigSpec;
import com.oncall.config.ConfigTier;
import com.oncall.config.OnCallConfigKeys;
import com.oncall.domain.governance.Operator;

import java.util.Set;

/**
 * 配置操作的权限判定。
 *
 * <p>判定的依据不是"这个人是谁"，而是<b>"这个改动会造成什么后果"</b>。
 * 把 {@code autonomy.level} 从 SUGGEST 改到 BOUNDED_AUTO 等价于提权，
 * 和"给某个用户加一个管理员账号"是同一类操作，因此需要同级别的管控。
 *
 * <p>三条规则：
 * <ol>
 *   <li><b>BACKEND_ONLY 项一律不可见</b>。不是返回 403，而是当作不存在返回 404——
 *       403 会泄露"这个键存在但你不能碰"，而兜底参数与凭据的存在性本身就是信息。
 *       这与工具网关的默认拒绝语义保持一致：未注册的工具不能通过错误信息泄露存在性。</li>
 *   <li><b>高危项需要双人复核</b>：发起与复核必须是两个不同的人，且复核方须为 ADMIN。</li>
 *   <li><b>高危清单本身不能是配置项</b>。见 {@link #HIGH_RISK_KEYS} 的说明。</li>
 * </ol>
 */
public final class ConfigAccessPolicy {

    /**
     * 需要双人复核的配置键。
     *
     * <p><b>刻意硬编码，不做成配置项</b>。如果这份清单可以由配置修改，
     * 那么"能把 autonomy.level 提到 BOUNDED_AUTO 的人"同样可以先把这个键
     * 从高危清单里摘掉，再从容修改它——双人复核就会被自己保护的机制绕过。
     * 守卫自己的东西不能交给被守卫的对象。要改这份清单只能改代码 + 走评审。
     *
     * <p>清单对应的后果：
     * <ul>
     *   <li>{@code autonomy.level} —— 提权，直接决定 AI 能否不经人同意就动手</li>
     *   <li>{@code autonomy.kill-switch-mode} —— 关掉最后一道闸</li>
     *   <li>{@code retrieval.rerank-enabled} —— 关掉会静默降低召回质量，
     *       表现为"回答变差"而不是报错，最难被发现</li>
     *   <li>{@code agent.max-steps} —— 调大等于放开成本与失控半径</li>
     *   <li>{@code alert.storm-threshold-per-minute} —— 调大会让真实告警风暴被判为正常</li>
     * </ul>
     *
     * <p>注意 {@code mcp.toolcallback-enabled} <b>不在</b>这份清单里——
     * 它是 {@code BACKEND_ONLY}，对前端根本不存在（404 而不是 403），
     * 比"需要两人复核"更强。它对应框架那个默认为 true 的自动注册开关，
     * 打开它等于关掉整个工具网关，不该有任何 UI 入口。
     */
    public static final Set<String> HIGH_RISK_KEYS = Set.of(
            OnCallConfigKeys.AUTONOMY_LEVEL,
            OnCallConfigKeys.AUTONOMY_KILL_SWITCH_MODE,
            OnCallConfigKeys.RETRIEVAL_RERANK_ENABLED,
            OnCallConfigKeys.AGENT_MAX_STEPS,
            OnCallConfigKeys.ALERT_STORM_THRESHOLD_PER_MINUTE
    );

    /** 待复核变更的有效期。过期后必须重新发起，理由见 {@link PendingChange}。 */
    public static final long PENDING_TTL_MILLIS = 15 * 60 * 1000L;

    /** 该键是否对前端可见。BACKEND_ONLY 一律不可见。 */
    public boolean isVisible(ConfigSpec spec) {
        return spec.tier() != ConfigTier.BACKEND_ONLY;
    }

    /**
     * 能否直接写入（无需第二人复核）。
     *
     * <p>高危项即使操作者是 ADMIN 也返回 false——ADMIN 拥有的是
     * <b>复核权</b>而不是<b>单独决定权</b>。
     */
    public boolean canWriteDirectly(Operator operator, ConfigSpec spec) {
        if (!isVisible(spec)) {
            return false;
        }
        if (requiresSecondApproval(spec.key())) {
            return false;
        }
        return operator.role() == Operator.Role.EDITOR || operator.role() == Operator.Role.ADMIN;
    }

    /** 能否发起一个待复核的高危变更。 */
    public boolean canPropose(Operator operator, ConfigSpec spec) {
        if (!isVisible(spec)) {
            return false;
        }
        return operator.role() == Operator.Role.EDITOR || operator.role() == Operator.Role.ADMIN;
    }

    /** 能否复核（批准）一个待复核变更。 */
    public boolean canApprove(Operator operator) {
        return operator.role() == Operator.Role.ADMIN;
    }

    public boolean requiresSecondApproval(String key) {
        return HIGH_RISK_KEYS.contains(key);
    }
}
