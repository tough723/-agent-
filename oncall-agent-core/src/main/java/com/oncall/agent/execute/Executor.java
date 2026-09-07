package com.oncall.agent.execute;

import com.oncall.agent.run.AgentStepStore;
import com.oncall.domain.autonomy.AlertSeverity;
import com.oncall.domain.autonomy.AutonomyGate;
import com.oncall.domain.autonomy.AutonomyLevel;
import com.oncall.domain.plan.Plan;
import com.oncall.domain.plan.PlanStep;
import com.oncall.domain.run.AgentRun;
import com.oncall.domain.run.AgentStep;
import com.oncall.domain.run.RunStatus;
import com.oncall.domain.run.StepStatus;
import com.oncall.domain.tool.ToolDeniedException;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.toolgateway.IdempotencyStore;
import com.oncall.toolgateway.KillSwitch;
import com.oncall.toolgateway.ToolPolicyEngine;
import org.springframework.ai.tool.ToolCallback;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 逐步执行一份计划。
 *
 * <h2>★ 本轮的技术要点：放权判定必须<b>逐步</b>做，不能整轮做</h2>
 * <p>一份计划天然混着两种步骤：只读探查（{@code k8s_get_pods}）和
 * 高危动作（{@code scale_replicas}）。如果在整轮开始时判定一次，
 * 只有两种结果，而且都错：
 * <ul>
 *   <li><b>整轮拒绝</b> —— 连只读诊断都做不了。而只读诊断恰恰是
 *       放权级别较低时<b>最有价值</b>的部分：人不该失去「AI 帮我把事实查清楚」
 *       这一层，只该失去「AI 替我动手」这一层。</li>
 *   <li><b>整轮放行</b> —— 一个 {@code SHADOW} 级别的运行会执行扩容。灾难。</li>
 * </ul>
 * 所以判定放在循环里：能做几步做几步，遇到不能自动执行的那一步就停下交回人工。
 * <b>已经查到的事实不会因为后面有一步不能自动执行而作废。</b>
 *
 * <h2>★ 遇到不能自动执行的步骤：交回人工，而不是跳过</h2>
 * <p>「跳过这一步继续跑」看起来更友好，但那是撒谎：计划说要扩容，
 * 结果没扩容却报告「执行完成」。运维会以为故障已经处置了。
 * 所以这里把整个 run 置为 {@link RunStatus#HANDED_OVER} 并说清停在哪一步。
 *
 * <h2>顺序：先 KillSwitch，再 AutonomyGate</h2>
 * <p>{@code AutonomyGate} 的类注释明确写了它<b>不</b>判断 kill switch，
 * 那是运行时的正交维度，要调用方先过 {@code assertAllowed()}。
 * 这个顺序不是风格问题：kill switch 是「现在整体不许动」，
 * 放权级别是「这类动作许不许自动」。前者是急停，必须先判。
 */
public final class Executor {

    private final ToolPolicyEngine policyEngine;
    private final ToolResolver tools;
    private final AgentStepStore stepStore;
    private final KillSwitch killSwitch;
    private final IdempotencyStore idempotencyKeys;
    private final Set<String> autoWhitelist;
    private final Clock clock;

    public Executor(ToolPolicyEngine policyEngine,
                    ToolResolver tools,
                    AgentStepStore stepStore,
                    KillSwitch killSwitch,
                    IdempotencyStore idempotencyKeys,
                    Set<String> autoWhitelist,
                    Clock clock) {
        this.policyEngine = Objects.requireNonNull(policyEngine, "policyEngine");
        this.tools = Objects.requireNonNull(tools, "tools");
        this.stepStore = Objects.requireNonNull(stepStore, "stepStore");
        this.killSwitch = Objects.requireNonNull(killSwitch, "killSwitch");
        this.idempotencyKeys = Objects.requireNonNull(idempotencyKeys, "idempotencyKeys");
        // Set.copyOf 拒绝 null 元素并返回不可变集合：
        // 若直接持有调用方给的 Set，对方事后 add 就能偷偷把一个工具放进自动执行白名单。
        this.autoWhitelist = Set.copyOf(Objects.requireNonNull(autoWhitelist, "autoWhitelist"));
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 执行计划。
     *
     * @param run      本次运行（携带放权级别快照与预算）
     * @param plan     已过 {@code PlanValidator} 的计划
     * @param severity 本次告警的级别——放权判定的输入之一
     */
    public ExecutionResult execute(AgentRun run, Plan plan, AlertSeverity severity) {
        Objects.requireNonNull(run, "run");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(severity, "severity");

        AgentRun current = run;
        int executed = 0;

        for (PlanStep step : plan.steps()) {
            // ① 预算：先判再花。花完再判等于允许超支一步。
            if (current.budgetExhausted()) {
                return stop(current, executed, RunStatus.ABORTED,
                        "预算耗尽，停在第 " + step.seq() + " 步之前（已执行 " + executed + " 步）");
            }

            // ② 白名单。resolve() 对未注册工具抛 ToolDeniedException。
            ToolPolicy policy;
            try {
                policy = policyEngine.resolve(step.action());
            } catch (ToolDeniedException e) {
                return stop(current, executed, RunStatus.FAILED,
                        "第 " + step.seq() + " 步的工具 " + e.toolName()
                                + " 不在白名单内：" + e.reason());
            }

            // ③ kill switch：急停优先于放权判定（见类注释）。
            try {
                killSwitch.assertAllowed(step.action(), policy.risk());
            } catch (RuntimeException e) {
                return stop(current, executed, RunStatus.ABORTED,
                        "第 " + step.seq() + " 步被 kill switch 拦下：" + e.getMessage());
            }

            // ④ ★ 放权判定——逐步，不是整轮。这是 AutonomyGate 唯一的生产调用点。
            boolean mayAuto = AutonomyGate.canAutoExecute(
                    current.autonomyLevel(), severity, policy,
                    autoWhitelist.contains(step.action()));
            if (!mayAuto) {
                return stop(current, executed, RunStatus.HANDED_OVER,
                        "第 " + step.seq() + " 步（" + step.action() + "，风险 "
                                + policy.risk() + "）在放权级别 " + current.autonomyLevel()
                                + " 下不允许自动执行，已执行 " + executed
                                + " 步的事实保留，后续交回人工");
            }

            // ⑤ 工具必须真的装配了。没装配 ≠ 不许执行，是「做不了」。
            Optional<ToolCallback> callback = tools.resolve(step.action());
            if (callback.isEmpty()) {
                return stop(current, executed, RunStatus.FAILED,
                        "第 " + step.seq() + " 步的工具 " + step.action() + " 未被装配");
            }

            // ⑥ 落库抢占。返回 false 说明这一步已被别的 worker 抢走
            //    （或上一次崩溃前已经做过），不重复执行。
            //
            // ★ 主键与幂等键都必须带「第几代计划」。重规划后新计划的 seq
            //   会从 1 重新开始，不带代号的话：
            //     - stepId 撞 agent_step 主键 → tryInsert 返回 false → 下面的
            //       continue 把这一步静默跳过，新计划的前几步就这么消失了；
            //     - 幂等键撞 uq_agent_step_idem → 同样被跳过。
            //   两条唯一约束都得换代，只改一条等于没修。
            //
            //   注：这与 GuardedToolCallback 自己的幂等键无关。那个撞键时是
            //   「重放上次的结果」（ledger.claim 失败 → return prior），是安全的；
            //   而这里撞键是 continue，会丢数据。两者也落在不同的存储
            //   （agent_step.idempotency_key vs ToolExecutionLedger），无需同形。
            int generation = current.usedReplans();
            String idemKey = idempotencyKeys.keyFor(
                    planScope(current.id(), generation), step.seq(),
                    step.action(), step.argsJson());
            AgentStep running = AgentStep.start(
                    stepId(current.id(), generation, step.seq()), current.id(), step.seq(),
                    step.action(), step.argsJson(), idemKey, clock.instant());
            if (!stepStore.tryInsert(running)) {
                continue;
            }

            // ⑦ 真正执行，并把结果写回这一步。
            String summary;
            try {
                String raw = callback.get().call(step.argsJson());
                summary = summarize(raw);
            } catch (RuntimeException e) {
                stepStore.finish(running.fail(
                        e.getClass().getSimpleName() + ": " + e.getMessage(), clock.instant()));
                return stop(current, executed, RunStatus.FAILED,
                        "第 " + step.seq() + " 步（" + step.action() + "）执行失败："
                                + e.getClass().getSimpleName());
            }
            stepStore.finish(running.succeed(summary, clock.instant()));

            current = current.consume(1, 0L, BigDecimal.ZERO);
            executed++;
        }

        return new ExecutionResult(current.finish(RunStatus.SUCCEEDED, clock.instant()),
                executed, null);
    }

    /**
     * 步骤主键。
     *
     * <p>用 {@code runId:seq} 而不是随机 ID：同一次运行的同一序号必须是同一个主键，
     * 这样崩溃重启后重跑会撞主键而不是插出一条重复步骤。
     */
    /**
     * 步骤主键：{@code runId:第几代计划:seq}。
     *
     * <p>★ 中间那一段是重规划代号（{@code AgentRun.usedReplans()}）。
     * 少了它，重规划后新计划的 {@code seq} 会从 1 重新开始，
     * 与上一代计划撞主键，{@code tryInsert} 返回 false，
     * 那一步被静默 {@code continue} 掉——<b>新计划的前几步会凭空消失</b>，
     * 而日志里看不出任何异常。
     *
     * <p>列宽：{@code agent_step.id} 是 {@code VARCHAR(64)}，而 {@code runId}
     * 本身就允许到 64。也就是说这个格式在极端情况下会超宽——
     * 但那是<b>先前就存在</b>的问题（原格式 {@code runId:seq} 同样会超），
     * 且 {@code AgentStep} 构造期的 {@code requireFits} 会抛而不是截断，
     * 所以它是显式失败而非静默错行。不在本增量里扩范围。
     */
    private static String stepId(String runId, int generation, int seq) {
        return runId + ":" + generation + ":" + seq;
    }

    /**
     * 幂等作用域：{@code runId#第几代计划}。
     *
     * <p>作为 {@code IdempotencyStore.keyFor} 的第一个参数传入。
     * 那个参数的名字叫 {@code runId}，但它的实际职责是「幂等键的作用域」——
     * 而重规划后的同一步是一次<b>新的尝试</b>，不该与上一代计划共享作用域，
     * 否则 {@code uq_agent_step_idem} 会把它判成重复执行而跳过。
     *
     * <p>刻意不改 {@code IdempotencyStore.keyFor} 的签名：那个接口也被
     * {@code GuardedToolCallback} 用着，而它撞键时的行为是「重放上次的结果」
     * （安全），与这里的 {@code continue}（丢数据）语义不同，两者无需同形。
     */
    private static String planScope(String runId, int generation) {
        return runId + "#" + generation;
    }

    /**
     * 工具原始输出可能很大，而 {@code result_summary} 是给人看的摘要。
     *
     * <p><b>截断而不是拒绝</b>：这里与主键/幂等键的处理刻意相反——
     * 那些截断会让标识指向别的对象，而摘要截断只是少看几个字。
     */
    private static String summarize(String raw) {
        if (raw == null) {
            return "(工具返回 null)";
        }
        int max = 500;
        return raw.length() <= max ? raw : raw.substring(0, max) + "…(已截断)";
    }

    private ExecutionResult stop(AgentRun run, int executed, RunStatus terminal, String reason) {
        return new ExecutionResult(run.finish(terminal, clock.instant()), executed, reason);
    }

    /** 本次运行允许的自动执行白名单（不可变副本）。 */
    public Set<String> autoWhitelist() {
        return autoWhitelist;
    }

    /** 供测试与诊断：确认放权级别确实来自 run 的快照而不是当前配置。 */
    public AutonomyLevel autonomyLevelOf(AgentRun run) {
        return run.autonomyLevel();
    }

    /** 供断言：一次执行最多推进到哪个步骤状态。 */
    public static StepStatus statusOf(AgentStep step) {
        return step.status();
    }
}
