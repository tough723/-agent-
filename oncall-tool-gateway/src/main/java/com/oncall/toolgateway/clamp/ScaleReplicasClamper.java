package com.oncall.toolgateway.clamp;

/**
 * 扩容参数夹紧器（Strategy 模式）—— 防提示注入后果的确定性防线。
 *
 * <p><b>核心思想：模型只提方向，不定数值。</b>
 * 即使模型被日志里的注入内容骗了、生成 {@code replicas: 0}，
 * 夹紧后也会被限制到 {@code minReplicas}，并留下审计记录。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li>目标副本数不得超过 {@code current + maxDelta}（限制单次爆炸半径）</li>
 *   <li>目标副本数不得低于 {@code minReplicas}（不允许缩到 0，那等于下线服务）</li>
 *   <li>当前副本数查询失败时<b>拒绝执行</b>——不允许"查不到就放行"</li>
 * </ol>
 *
 * <p>本实现刻意不解析 JSON（避免在核心安全逻辑里引入解析依赖与异常路径）。
 * 真实的 JSON 适配放在 {@code JsonScaleArgsAdapter}（M1 待办），
 * 由它把 {@code String} 转成 {@link ScaleRequest} 后再调这里。
 */
public class ScaleReplicasClamper {

    private final ReplicaStatePort replicaState;
    private final PolicyProvider policyProvider;

    public ScaleReplicasClamper(ReplicaStatePort replicaState, PolicyProvider policyProvider) {
        this.replicaState = replicaState;
        this.policyProvider = policyProvider;
    }

    /**
     * 夹紧扩容请求。
     *
     * @return 夹紧结果，含最终值与是否被修改（被修改时必须记审计）
     */
    public ClampResult clamp(ScaleRequest request) {
        Limits limits = policyProvider.limitsFor(request.service());

        // ③ 查不到当前值就拒绝，不允许放行
        int current = replicaState.currentReplicas(request.service());

        // ① 上限：current + maxDelta
        int upper = current + limits.maxDelta();
        // ② 下限：minReplicas
        int target = Math.min(request.replicas(), upper);
        target = Math.max(target, limits.minReplicas());

        boolean clamped = target != request.replicas();
        return new ClampResult(target, clamped, current, upper, limits.minReplicas());
    }

    /** 工具名常量，供 ArgClamper 适配层与策略配置引用。 */
    public static final String TOOL_NAME = "scale_replicas";

    /**
     * @param service  目标服务
     * @param replicas 模型提议的副本数
     */
    public record ScaleRequest(String service, int replicas) {}

    /**
     * @param maxDelta    单次允许的最大增幅（限制爆炸半径）
     * @param minReplicas 副本数下限（绝不允许低于此值）
     */
    public record Limits(int maxDelta, int minReplicas) {}

    /**
     * @param target      夹紧后的副本数
     * @param clamped     是否发生了夹紧（true 时必须记审计 + 告警）
     * @param current     夹紧前的当前副本数
     * @param upperBound  本次允许的上限
     * @param lowerBound  本次允许的下限
     */
    public record ClampResult(int target, boolean clamped, int current, int upperBound, int lowerBound) {}

    /** 按服务提供夹紧策略（不同服务的重要性不同，min/max 应可分别配置）。 */
    @FunctionalInterface
    public interface PolicyProvider {
        Limits limitsFor(String service);
    }
}
