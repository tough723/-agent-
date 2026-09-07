package com.oncall.domain.plan;

import java.util.List;
import java.util.Objects;

/**
 * Planner 的产出：一份待执行的排查计划。
 *
 * <h2>★ 为什么构造期就要求 {@code seq} 必须是 1..n 连续且有序</h2>
 * <p>{@code PlanValidator} 的顺序约束（写操作前必须有足够的信息收集步骤）
 * 依赖「第 k 步之前有哪几步」这个事实。若 {@code seq} 可以是
 * {@code [1, 5, 9]} 或 {@code [3, 1, 2]}，那么「之前」就没有确定含义，
 * 顺序约束会静默失效——而它是 F2.3 三条确定性防线之一。
 *
 * <p>把这条不变量放在构造期而不是校验器里，是因为
 * <b>校验器可以被绕过，构造函数不能</b>。
 *
 * <h2>为什么拒绝空计划</h2>
 * <p>空计划意味着 Planner 认为「什么都不用做」。这应当是
 * Reporter 的一个显式结论（「无需处置」），而不是一份空计划——
 * 否则「模型没输出」和「模型判断无需处置」在数据上无法区分。
 */
public record Plan(List<PlanStep> steps) {

    public Plan {
        Objects.requireNonNull(steps, "steps");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException(
                    "计划不得为空——「无需处置」应当是 Reporter 的显式结论，"
                            + "而不是一份空计划；否则「模型没输出」与「模型判断无需处置」无法区分");
        }
        for (int i = 0; i < steps.size(); i++) {
            int expected = i + 1;
            int actual = steps.get(i).seq();
            if (actual != expected) {
                throw new IllegalArgumentException("第 " + expected + " 个位置的步骤 seq=" + actual
                        + "——seq 必须是从 1 开始的连续整数且按序排列，"
                        + "否则 PlanValidator 的「写操作前必须有足够的信息收集步骤」会静默失效");
            }
        }
    }

    public static Plan of(PlanStep... steps) {
        return new Plan(List.of(steps));
    }

    public int size() {
        return steps.size();
    }

    /** 计划里是否含高危动作。判定需要工具策略，故由 {@code PlanValidator} 负责。 */
    public PlanStep step(int seq) {
        if (seq < 1 || seq > steps.size()) {
            throw new IllegalArgumentException("seq=" + seq + " 超出计划范围 1.." + steps.size());
        }
        return steps.get(seq - 1);
    }
}
