package com.oncall.domain.run;

/**
 * 一次排查的生命周期状态。
 *
 * <p>取值与 {@code db/migration/V2__agent_execution.sql} 中
 * {@code agent_run.status} 的列注释逐字对应：
 * {@code RUNNING / SUCCEEDED / FAILED / ABORTED / HANDED_OVER}。
 *
 * <p><b>为什么不用字符串</b>：这五个值此前只存在于一条 SQL 注释里，
 * Java 侧没有任何类型承载它。字符串状态下「HANDED_OVER 拼成 HANDEDOVER」
 * 不会有编译器报错，只会在查询时静默查不到。
 */
public enum RunStatus {

    /** 正在排查。唯一非终态。 */
    RUNNING,

    /** 排查完成并给出了结论。 */
    SUCCEEDED,

    /** 排查过程本身失败（不是「结论是失败」）。 */
    FAILED,

    /** 被预算护栏或人工中止。 */
    ABORTED,

    /** 超出自动化边界，已移交人工。与 {@code AutonomyLevel} 的放权边界直接相关。 */
    HANDED_OVER;

    /** 是否终态。终态的 run 不再推进 {@code step_cursor}，也不再消耗预算。 */
    public boolean isTerminal() {
        return this != RUNNING;
    }
}
