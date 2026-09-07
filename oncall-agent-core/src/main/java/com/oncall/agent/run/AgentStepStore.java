package com.oncall.agent.run;

import com.oncall.domain.run.AgentStep;

import java.util.List;
import java.util.Optional;

/**
 * {@code agent_step} 的存取端口。
 *
 * <h2>★ 为什么是 {@code tryInsert} 返回布尔，而不是 {@code insert} 抛异常</h2>
 * <p>{@code uq_agent_step_idem} 的 UNIQUE 约束是幂等的<b>物理</b>保证
 * （V2 列注释原文）。多实例下两个 worker 同时拿到同一个重投消息，
 * 各自查内存都说「没执行过」，于是都去插入 —— 只有一个会成功。
 *
 * <p>关键在于：<b>「别人已经抢到了」是预期行为，不是故障。</b>
 * 如果把它和其他数据库错误混成同一个异常抛出去，调用方只有两条路，
 * 而且两条都错：
 * <ul>
 *   <li>当成故障重试 —— 永远撞同一个唯一约束，白转到超时；</li>
 *   <li>当成故障吞掉 —— 这一步凭空消失，且账面上看不出被跳过。</li>
 * </ul>
 * 所以这里用返回值把两种结果分开：{@code false} 表示<b>别人已经抢到</b>，
 * 调用方应当直接去读那一步的既有结果；真正的数据库故障仍然抛出来。
 *
 * <h2>调用时机</h2>
 * <p>唯一约束是<b>探测器不是阻止器</b>：冲突发生在插入的那一刻。
 * 所以抢占必须体现为「先插入、再执行」，
 * 绝不能写成「先查有没有、没有就执行、执行完再记」——
 * 那样两个线程可以同时通过查询然后都执行。
 */
public interface AgentStepStore {

    /**
     * 尝试抢占这一步。
     *
     * @return {@code true} 表示本调用抢到了、应当继续执行；
     *         {@code false} 表示幂等键已存在（别人已经抢到），应当去读既有结果
     * @throws IllegalStateException 真正的数据库故障——与「抢占失败」必须可区分
     */
    boolean tryInsert(AgentStep step);

    Optional<AgentStep> findById(String id);

    /** 按 {@code (run_id, seq)} 升序返回，用于断点续跑与事后复盘。 */
    List<AgentStep> findByRunId(String runId);

    /** 只写收尾四列：状态、结果、错误信息、完成时刻。 */
    void finish(AgentStep step);
}
