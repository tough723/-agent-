package com.oncall.domain.run;

import com.oncall.domain.autonomy.AutonomyLevel;
import com.oncall.domain.trace.TraceId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * 一次排查的全过程 —— {@code agent_run} 表的一行。
 *
 * <p>14 个组件与 V2 的 14 列一一对应。三个不变量刻意写在构造期，
 * 而不是留给调用方去检查：
 *
 * <h2>① 放权等级是快照，不是引用</h2>
 * <p>V2 的列注释原文：「放权等级快照，不是引用——授权条件必须固定」。
 * 放权等级是 RUNTIME_HOT 配置，会随时间变化；但「<b>当时</b>是在什么授权下做的」
 * 必须在事后追责时有唯一答案。本类是 record，没有 setter，
 * 而且<b>刻意不提供任何 {@code withAutonomyLevel} 之类的方法</b>。
 *
 * <p>光有 record 的不可变性还不够——持久层若把这一列写进 UPDATE 语句，
 * 快照就会在每次进度更新时被当前配置覆盖，而且不会有任何报错。
 * 所以 {@code JdbcAgentRunStore} 的 UPDATE 语句<b>刻意不含这一列</b>，
 * 并由测试直接钉住。
 *
 * <h2>② 四项预算护栏不允许被越过</h2>
 * <p>步数 / token / 成本 / 重规划次数四项，{@code used} 一旦超过 {@code budget} 就是构造失败。
 * 若允许越界，护栏就只是记录而不是护栏。
 *
 * <h2>③ {@code finished_at} 与终态互为充要</h2>
 * <p>终态必须有完成时刻，非终态必须没有。否则「这次排查什么时候结束的」
 * 这个问题会没有答案，或者一个还在跑的 run 声称自己已经结束。
 */
public record AgentRun(
        String id,
        TraceId traceId,
        String alertGroupId,
        RunStatus status,
        AutonomyLevel autonomyLevel,
        int stepCursor,
        int budgetSteps,
        long budgetTokens,
        BigDecimal budgetCost,
        int budgetReplans,
        int usedSteps,
        long usedTokens,
        BigDecimal usedCost,
        int usedReplans,
        Instant createdAt,
        Instant finishedAt) {

    /** {@code id} / {@code alert_group_id} 的列宽 VARCHAR(64)——超宽必须拒绝，绝不截断。 */
    public static final int MAX_ID_LENGTH = 64;

    public AgentRun {
        requireFits(id, "id");
        Objects.requireNonNull(traceId, "traceId：没有 trace 的 run 无法与审计行关联");
        if (alertGroupId != null) {
            requireFits(alertGroupId, "alertGroupId");
        }
        Objects.requireNonNull(status, "status");
        // NOT NULL 列，而且它是快照：缺了它就无法回答「当时被授权到哪一级」。
        Objects.requireNonNull(autonomyLevel,
                "autonomyLevel：放权等级快照不允许为空——事后无从判断当时被授权到哪一级");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(budgetCost, "budgetCost");
        Objects.requireNonNull(usedCost, "usedCost");

        if (stepCursor < 0) {
            throw new IllegalArgumentException("stepCursor 不得为负：" + stepCursor);
        }
        // 预算为 0 意味着一步都不能走。那是配置错误，不是一次合法的排查。
        if (budgetSteps <= 0 || budgetTokens <= 0 || budgetCost.signum() <= 0) {
            throw new IllegalArgumentException("预算三项必须全部为正，否则这次排查一步都走不了："
                    + "steps=" + budgetSteps + " tokens=" + budgetTokens + " cost=" + budgetCost);
        }
        // ★ 重规划预算刻意允许为 0，与上面三项「必须为正」不同。
        //   步数/token/成本预算为 0 意味着「一步都走不了」，那是配置错误；
        //   而重规划预算为 0 意味着「按最初计划一路走到底」，那是一种合法策略。
        if (budgetReplans < 0 || usedReplans < 0) {
            throw new IllegalArgumentException("重规划预算与已用次数不得为负："
                    + "budget=" + budgetReplans + " used=" + usedReplans);
        }
        if (usedReplans > budgetReplans) {
            throw new IllegalArgumentException("已重规划次数越过预算——护栏形同虚设："
                    + usedReplans + "/" + budgetReplans);
        }
        if (usedSteps < 0 || usedTokens < 0 || usedCost.signum() < 0) {
            throw new IllegalArgumentException("已用量不得为负："
                    + "steps=" + usedSteps + " tokens=" + usedTokens + " cost=" + usedCost);
        }
        // ★ 步数/token/成本三项护栏（重规划次数的护栏在下面单独一条）。
        //   用 compareTo 而不是 equals：NUMERIC(12,6) 下 0.1 与 0.10 相等但不 equals。
        if (usedSteps > budgetSteps || usedTokens > budgetTokens
                || usedCost.compareTo(budgetCost) > 0) {
            throw new IllegalArgumentException("已用量越过预算——护栏形同虚设："
                    + "steps=" + usedSteps + "/" + budgetSteps
                    + " tokens=" + usedTokens + "/" + budgetTokens
                    + " cost=" + usedCost + "/" + budgetCost);
        }
        // ★ finished_at 与终态互为充要。
        if (status.isTerminal() && finishedAt == null) {
            throw new IllegalArgumentException(
                    "status=" + status + " 是终态，finishedAt 不得为空——否则无法回答「什么时候结束的」");
        }
        if (!status.isTerminal() && finishedAt != null) {
            throw new IllegalArgumentException(
                    "status=RUNNING 却带了 finishedAt=" + finishedAt + "——还在跑的排查不能声称已结束");
        }
    }

    private static void requireFits(String value, String field) {
        Objects.requireNonNull(value, field + "：不得为 null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + "：不得为空白");
        }
        // 绝不截断：截断后的主键会指向另一行，或让两次不同的排查撞成同一个 id。
        if (value.length() > MAX_ID_LENGTH) {
            throw new IllegalArgumentException(field + " 超过列宽 " + MAX_ID_LENGTH
                    + "（实际 " + value.length() + "）——绝不截断，截断会让主键指向别的行");
        }
    }

    /**
     * 开一次新排查：游标与四项用量都从 0 起，状态 RUNNING，未完成。
     *
     * @param autonomyLevel 开跑那一刻的放权等级，此后固定不变
     */
    public static AgentRun start(String id, TraceId traceId, String alertGroupId,
                                 AutonomyLevel autonomyLevel,
                                 int budgetSteps, long budgetTokens, BigDecimal budgetCost,
                                 int budgetReplans, Instant createdAt) {
        return new AgentRun(id, traceId, alertGroupId, RunStatus.RUNNING, autonomyLevel,
                0, budgetSteps, budgetTokens, budgetCost, budgetReplans,
                0, 0L, BigDecimal.ZERO, 0, createdAt, null);
    }

    /**
     * 消耗预算并推进游标，返回新实例（本类不可变）。
     *
     * <p><b>游标与 {@code usedSteps} 刻意同步推进</b>：游标是断点续跑的位置，
     * 若它落后于已花掉的步数，worker 崩溃重启后会重跑那些<b>已经付过费</b>的步骤——
     * 预算护栏会在重跑时被二次消耗，而账面上看不出重复。
     *
     * @throws IllegalArgumentException 若本次消耗会让任一项越过预算
     */
    public AgentRun consume(int stepsDelta, long tokensDelta, BigDecimal costDelta) {
        if (stepsDelta < 0 || tokensDelta < 0) {
            throw new IllegalArgumentException("消耗量不得为负：steps=" + stepsDelta
                    + " tokens=" + tokensDelta);
        }
        Objects.requireNonNull(costDelta, "costDelta");
        if (costDelta.signum() < 0) {
            throw new IllegalArgumentException("消耗成本不得为负：" + costDelta);
        }
        if (status.isTerminal()) {
            throw new IllegalStateException(
                    "status=" + status + " 已是终态，不再推进游标也不再消耗预算");
        }
        return new AgentRun(id, traceId, alertGroupId, status, autonomyLevel,
                stepCursor + stepsDelta,
                budgetSteps, budgetTokens, budgetCost, budgetReplans,
                usedSteps + stepsDelta, usedTokens + tokensDelta, usedCost.add(costDelta),
                usedReplans, createdAt, finishedAt);
    }

    /**
     * 收尾。放权等级快照、traceId、四项预算与 createdAt 原样带过去——
     * 它们是<b>写入一次</b>的列，收尾不该动它们。
     *
     * @param terminal 必须是终态
     */
    public AgentRun finish(RunStatus terminal, Instant at) {
        Objects.requireNonNull(terminal, "terminal");
        Objects.requireNonNull(at, "at");
        if (!terminal.isTerminal()) {
            throw new IllegalArgumentException("finish 需要终态，收到 " + terminal);
        }
        if (status.isTerminal()) {
            throw new IllegalStateException("已经是 " + status + "，不能再次收尾为 " + terminal);
        }
        return new AgentRun(id, traceId, alertGroupId, terminal, autonomyLevel,
                stepCursor, budgetSteps, budgetTokens, budgetCost, budgetReplans,
                usedSteps, usedTokens, usedCost, usedReplans, createdAt, at);
    }

    /**
     * 从终态回到 {@code RUNNING}，并消耗一次重规划配额。
     *
     * <h2>★ 为什么要求「必须是终态」——这与直觉相反</h2>
     * <p>重规划换掉的是<b>剩下要走的步骤</b>。一次还在跑的执行没有「剩下」，
     * 所以<b>只有停下来的排查才有可重规划的东西</b>。
     *
     * <p>这不是理论推演：{@code Executor.execute()} 的两条返回路径都会调
     * {@link #finish}，所以 {@code ExecutionResult.run()} <b>必定是终态</b>。
     * 本方法早先的守卫方向是反的（终态即拒），后果是
     * {@code Replanner} 永远拿不到一个能用的 run ——
     * <b>重规划在结构上进不了循环</b>，而每个单元测试都还是绿的，
     * 因为测试喂进去的是手工构造的 RUNNING run，不是 {@code Executor} 的真实输出。
     *
     * <h2>★ 为什么 SUCCEEDED 与 HANDED_OVER 不可恢复</h2>
     * <ul>
     *   <li>{@code SUCCEEDED}：没有可重规划的东西，重规划只会白扣一次预算；</li>
     *   <li>{@code HANDED_OVER}：人已经接手了，机器再改主意是越权。</li>
     * </ul>
     * 只有 {@code FAILED} 与 {@code ABORTED} 是「机器自己停下来、还可以再试」的状态。
     *
     * <h2>★ 为什么 finishedAt 必须清空</h2>
     * <p>构造期不变量规定 {@code finished_at} 与终态<b>互为充要</b>。
     * 回到 RUNNING 却留着 finishedAt，会直接违反那条不变量；
     * 而留着它更实际的坏处是：一个还在跑的排查会声称自己已经结束。
     *
     * <p><b>为什么是独立方法而不是并入 {@link #consume}</b>：
     * 重规划不消耗步数。若并入 {@code consume}，调用方就得传
     * {@code consume(0, tokens, cost)} 来表达「只重规划、不走步」，
     * 那个 0 会被读成「走了 0 步」。
     *
     * @throws IllegalStateException 若尚未结束、已成功、已交回人工，或预算已耗尽
     */
    public AgentRun resumeForReplan() {
        if (!status.isTerminal()) {
            throw new IllegalStateException("status=" + status
                    + " 尚未结束，无从重规划——重规划换掉的是「剩下要走的步骤」，"
                    + "一次还在跑的执行没有「剩下」");
        }
        if (status == RunStatus.SUCCEEDED) {
            throw new IllegalStateException(
                    "status=SUCCEEDED 已成功，没有可重规划的东西——重规划只会白扣一次预算");
        }
        if (status == RunStatus.HANDED_OVER) {
            throw new IllegalStateException(
                    "status=HANDED_OVER 已交回人工，人已经接手了，机器再改主意是越权");
        }
        if (usedReplans + 1 > budgetReplans) {
            // 抛异常而不是静默截断：重规划预算耗尽是一个终止条件，
            // 调用方必须知道并据此收尾，而不是以为这次重规划生效了。
            throw new IllegalStateException("重规划预算已耗尽："
                    + usedReplans + "/" + budgetReplans
                    + "——这是终止条件，应当收尾而不是继续改主意");
        }
        return new AgentRun(id, traceId, alertGroupId, RunStatus.RUNNING, autonomyLevel,
                stepCursor, budgetSteps, budgetTokens, budgetCost, budgetReplans,
                usedSteps, usedTokens, usedCost, usedReplans + 1, createdAt, null);
    }

    /** 重规划配额是否已经用尽。用尽即该收尾，而不是再改一次主意。 */
    public boolean replanBudgetExhausted() {
        return usedReplans >= budgetReplans;
    }

    /**
     * 步数 / token / 成本三项护栏是否已经用尽。用尽即该收尾，而不是再走一步。
     *
     * <p><b>刻意不含重规划次数</b>：那是第四个独立计数器，由
     * {@link #replanBudgetExhausted()} 单独判。合并成一个布尔会让
     * 「排查太长」与「反复改主意」在调用方眼里变成同一件事，
     * 而它们的处置方式不同（前者调步数预算，后者查 prompt）。
     */
    public boolean budgetExhausted() {
        return usedSteps >= budgetSteps
                || usedTokens >= budgetTokens
                || usedCost.compareTo(budgetCost) >= 0;
    }
}
