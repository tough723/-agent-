package com.oncall.agent.run;

import com.oncall.domain.run.AgentRun;

import java.util.Optional;

/**
 * {@code agent_run} 的存取端口。
 *
 * <h2>为什么是端口而不是直接给 JDBC 实现</h2>
 * <p>编排层要能在没有数据库的情况下被单元测试；而多实例部署下
 * {@code step_cursor} 的断点续跑必须由数据库承载，内存实现毫无意义。
 * 两者都要，所以先立端口。
 *
 * <h2>{@link #update} 的一条硬约束</h2>
 * <p>它只推进<b>进度</b>：状态、游标、三项用量、完成时刻。
 * {@code autonomy_level}、{@code trace_id}、三项预算与 {@code created_at}
 * 是<b>写入一次</b>的列，{@code update} 不允许碰它们。
 *
 * <p>其中 {@code autonomy_level} 尤其要紧：它是 RUNTIME_HOT 配置，
 * 会随时间变化，而 V2 的列注释要求它固定为「当时的」快照。
 * 若 {@code update} 把它一并写回去，每次进度更新都会用<b>当前</b>配置
 * 覆盖<b>当时</b>的授权，而且不会有任何报错——
 * 事后追责时读到的放权等级就不是真正生效的那一个。
 */
public interface AgentRunStore {

    /** 插入一次新排查。主键冲突必须抛出来，不得吞掉——那意味着 id 生成出了问题。 */
    void insert(AgentRun run);

    Optional<AgentRun> findById(String id);

    /** 只推进进度；不碰放权快照、traceId、预算与创建时刻。见接口说明。 */
    void update(AgentRun run);
}
