package com.oncall.agent.plan;

import com.oncall.domain.plan.Plan;
import com.oncall.domain.run.AgentRun;

import java.util.Objects;

/**
 * 一次重规划的结果 —— <b>新计划与扣过预算的 run 绑定在一起</b>。
 *
 * <h2>★ 为什么必须绑在一起返回</h2>
 * <p>{@link AgentRun#resumeForReplan()} 返回的是一个<b>新的</b>不可变 run。
 * 如果 {@link Replanner} 只返回 {@link Plan}，调用方就得记得自己去扣预算 ——
 * 而一旦忘了，<b>重规划预算永远不会减少</b>：
 * {@code used_replans} 停在 0，{@code budget_replans} 形同虚设，
 * 循环可以无限改主意而每一步看起来都合法。
 *
 * <p>绑在一起之后，「拿到新计划却没扣预算」在结构上就不可能 ——
 * 这是靠构造保证一致，不是靠调用方记得。
 *
 * <p>同理，构造期就断言 run 确实被扣过：如果传进来的 run 的
 * {@code usedReplans} 没有增加，说明调用方传错了对象。
 */
public record ReplanOutcome(AgentRun run, Plan plan) {

    public ReplanOutcome {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(plan, "plan");
        if (run.usedReplans() < 1) {
            throw new IllegalArgumentException(
                    "重规划结果的 run.usedReplans=" + run.usedReplans()
                            + "——一次成功的重规划必然已经扣过预算，"
                            + "传进来的一定是扣之前的旧 run");
        }
        // 这里<b>刻意不再检查</b>「计划是否为空」：{@link Plan} 的构造器已经拒绝了
        // 空计划（理由就写在那儿——「无需处置」应当是 Reporter 的显式结论）。
        // 再查一次是死代码，而死代码会让人以为这里还有一道防线。
    }
}
