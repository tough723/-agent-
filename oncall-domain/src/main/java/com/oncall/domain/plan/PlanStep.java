package com.oncall.domain.plan;

import java.util.List;
import java.util.Objects;

/**
 * 计划里的一步。
 *
 * <h2>★ 为什么 {@code basisRefs} 是必填而不能为空</h2>
 * <p>{@code 修复方案.md} F2.3 要求「高危步骤必须能追溯到可信来源」，
 * 并配套要求 <b>Planner 输出时必须声明每步的依据来源</b>。
 *
 * <p>如果允许一步「没有依据」，那么校验器就无法区分
 * 「Planner 老实交代了它没有依据」与「Planner 忘了填这个字段」——
 * 而这两种情况在安全上必须同样被拒绝。
 * 所以这里在<b>构造期</b>就要求至少一条依据，
 * 让「无依据的高危步骤」根本无法被构造出来，而不是等到校验时再拦。
 *
 * <h2>为什么 {@code action} 不在这里校验白名单</h2>
 * <p>本类在 {@code oncall-domain}（零依赖），拿不到 {@code ToolPolicyEngine}。
 * 白名单校验属于 {@code PlanValidator}——<b>刻意不在两处各校验一半</b>，
 * 否则会分叉（本项目已因此错过多次）。
 */
public record PlanStep(
        int seq,
        String action,
        String argsJson,
        List<BasisRef> basisRefs) {

    public static final int MAX_ACTION_LENGTH = 191;

    public PlanStep {
        Objects.requireNonNull(action, "action");
        if (action.isBlank()) {
            throw new IllegalArgumentException("action 不得为空白");
        }
        // 绝不截断：截断后的工具名会解析成另一个工具，或落到「未注册即拒绝」。
        if (action.length() > MAX_ACTION_LENGTH) {
            throw new IllegalArgumentException("action 超过列宽 " + MAX_ACTION_LENGTH
                    + "（实际 " + action.length() + "）——绝不截断，截断会指向别的工具");
        }
        if (seq < 1) {
            throw new IllegalArgumentException("seq 从 1 开始（与 F2.3 的 step 编号一致），实际 " + seq);
        }
        Objects.requireNonNull(basisRefs, "basisRefs");
        // ★ List.copyOf 会拒绝 null 元素，且返回不可变列表——
        //   若直接持有调用方给的 List，对方事后 add 就能偷偷给高危步骤补一条「可信依据」。
        basisRefs = List.copyOf(basisRefs);
        if (basisRefs.isEmpty()) {
            throw new IllegalArgumentException("seq=" + seq + " 没有任何依据（basisRefs 为空）——"
                    + "无法区分「Planner 交代了它没有依据」与「忘了填这个字段」，两者都必须拒绝");
        }
        // argsJson 可空：只读工具常常不需要参数。
    }

    /** 这一步是否有<b>可信</b>依据（告警规则或 Runbook；日志文本与工具输出不算）。 */
    public boolean hasTrustedBasis() {
        return basisRefs.stream().anyMatch(BasisRef::isTrusted);
    }
}
