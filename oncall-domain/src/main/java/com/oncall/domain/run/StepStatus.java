package com.oncall.domain.run;

/**
 * 单步的状态。
 *
 * <h2>★ 这组取值是本项目<b>首次定义</b>的，不是转录自某份规范</h2>
 * <p>与 {@link RunStatus} 不同：{@code agent_run.status} 在 V2 里有列注释
 * 逐字枚举了五个取值，而 {@code agent_step.status} 在 DDL 里
 * <b>既没有取值注释也没有 CHECK 约束</b>，设计文档里也没有枚举，
 * 全仓此前不存在 {@code StepStatus} 类型。
 *
 * <p>所以这里刻意只取<b>能从已有列结构推导出来</b>的最小集合，
 * 每一个值都对应一个真实存在的列，不引入任何凭空的状态：
 * <ul>
 *   <li>{@code started_at} 是 <b>NOT NULL</b> ⇒ 一行一旦存在就已经开始了，
 *       因此<b>不存在「尚未开始」的状态</b>。
 *       {@code PENDING} 会与这条列约束直接矛盾，所以刻意不收。</li>
 *   <li>{@code finished_at} 可空 ⇒ 「进行中」与「已结束」必须可分。</li>
 *   <li>{@code result_summary} 与 {@code error_message} 都可空
 *       ⇒ 终态里「成功」与「失败」必须可分。</li>
 * </ul>
 *
 * <p><b>刻意没有加的</b>：{@code SKIPPED} / {@code TIMED_OUT} / {@code DENIED}
 * 之类。它们是否需要，取决于「被审批闸门拒掉的一步算不算一步」——
 * 那是产品决策，不该由一个枚举的作者顺手定下来。
 * 真要加，必须同时给 V2 补一条 CHECK 约束，否则数据库仍然什么值都收。
 */
public enum StepStatus {

    /** 已开始、未结束。唯一非终态。 */
    RUNNING,

    /** 结束且拿到了结果。 */
    SUCCEEDED,

    /** 结束且失败——{@code error_message} 必须有值，否则这一步无从排查。 */
    FAILED;

    public boolean isTerminal() {
        return this != RUNNING;
    }
}
