package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolDeniedException;
import com.oncall.domain.tool.ToolSource;
import com.oncall.domain.tool.ToolPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具安全装饰器的验收测试——对应修复方案 F1.7 的验收标准。
 *
 * <p>这个类是整个安全模型的骨架，七道关卡**顺序不可调换**，
 * 所以这里不只测「每道关卡单独生效」，还要测**关卡之间的先后关系**：
 * 例如未注册工具必须在 kill switch 之前被拒（否则未注册工具的存在性会被泄露），
 * kill switch 必须在参数夹紧之前（否则被禁的工具还会留下夹紧审计，制造噪音与误导）。
 *
 * <p>全部用 fake 依赖，不启 Spring 上下文——装饰器逻辑本身不该依赖容器。
 */
class GuardedToolCallbackTest {

    private static final String TOOL = "scale_replicas";
    private static final String READ_TOOL = "query_metrics";
    /** 审计上下文：trace_id 是 NOT NULL，且必须能指回触发这次调用的排查。 */
    private static final ToolAuditContext CTX =
            new ToolAuditContext("trace-gtc", "run-1", "step-1", "alice");

    private ToolPolicyEngine policyEngine;
    private KillSwitch killSwitch;
    private RecordingAudit audit;
    private IdempotencyStore idempotency;
    private InMemoryToolExecutionLedger ledger;

    @BeforeEach
    void setUp() {
        policyEngine = new ToolPolicyEngine(List.of(
                ToolPolicy.highRisk(TOOL, Duration.ofMinutes(15)),
                ToolPolicy.readOnly(READ_TOOL)
        ));
        killSwitch = new KillSwitch();
        audit = new RecordingAudit();
        idempotency = new Sha256IdempotencyStore();
        // 用真实的内存实现而不是测试替身：抢占的原子性正是要被测的东西，
        // 用替身就等于把断言写在被测代码里。
        ledger = new InMemoryToolExecutionLedger();
    }

    // ---------------------------------------------------------- ① 默认拒绝

    @Test
    @DisplayName("① 未注册工具被拒，且原始工具根本不会被调用")
    void unregisteredToolIsDeniedAndNeverExecuted() {
        RecordingTool tool = RecordingTool.ok("rogue_tool", "pwned");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        assertThatThrownBy(() -> guarded.call("{}"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("rogue_tool")
                .hasMessageContaining("not in allowlist");

        assertThat(tool.calls).isZero();
        assertThat(ledger.size()).as("被拒的调用不该留下幂等记录").isZero();
    }

    @Test
    @DisplayName("① 策略引擎的拒绝回调被触发——MCP 运行期偷偷新增工具要能告警")
    void denialListenerIsNotified() {
        List<String> denied = new ArrayList<>();
        ToolPolicyEngine engine = new ToolPolicyEngine(
                List.of(ToolPolicy.readOnly(READ_TOOL)),
                (tool, reason) -> denied.add(tool + ":" + reason));

        GuardedToolCallback guarded = new GuardedToolCallback(
                RecordingTool.ok("sneaky_mcp_tool", "x"), engine, killSwitch,
                autoApprove(), audit, CTX, idempotency, ledger, ArgClamper.NOOP, "run-1", 1);

        assertThatThrownBy(() -> guarded.call("{}")).isInstanceOf(ToolDeniedException.class);
        assertThat(denied).containsExactly("sneaky_mcp_tool:not in allowlist");
    }

    @Test
    @DisplayName("① 撤销注册后立即失效，不需要重启")
    void revokedToolIsDeniedImmediately() {
        RecordingTool tool = RecordingTool.ok(TOOL, "ok");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        policyEngine.revoke(TOOL);

        assertThatThrownBy(() -> guarded.call("{}")).isInstanceOf(ToolDeniedException.class);
        assertThat(tool.calls).isZero();
    }

    // ---------------------------------------------------------- ② kill switch

    @Test
    @DisplayName("② OFF 模式下连只读工具也拒绝")
    void offModeBlocksEverything() {
        killSwitch.set(RunMode.OFF);
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "metrics");

        assertThatThrownBy(() -> guard(tool, ArgClamper.NOOP, autoApprove()).call("{}"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("mode=OFF");
        assertThat(tool.calls).isZero();
    }

    @Test
    @DisplayName("② READ_ONLY 模式下写工具被拒")
    void readOnlyModeBlocksWriteTools() {
        killSwitch.set(RunMode.READ_ONLY);
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");

        assertThatThrownBy(() -> guard(tool, ArgClamper.NOOP, autoApprove()).call("{}"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("write tool blocked");
        assertThat(tool.calls).isZero();
    }

    @Test
    @DisplayName("② READ_ONLY 模式下只读工具照常放行")
    void readOnlyModeAllowsReadTools() {
        killSwitch.set(RunMode.READ_ONLY);
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "metrics");

        String result = guard(tool, ArgClamper.NOOP, autoApprove()).call("{}");

        assertThat(result).isEqualTo("metrics");
        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("② kill switch 是热生效的：切档后下一次调用立即改变行为，无需重建装饰器")
    void killSwitchTakesEffectWithoutRebuild() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        assertThat(guarded.call("{\"replicas\":3}")).isEqualTo("scaled");

        killSwitch.set(RunMode.READ_ONLY);

        assertThatThrownBy(() -> guarded.call("{\"replicas\":4}"))
                .isInstanceOf(ToolDeniedException.class);
    }

    // ---------------------------------------------------------- ③ 参数夹紧

    @Test
    @DisplayName("③ 原始工具收到的是夹紧后的参数，不是模型生成的参数")
    void delegateReceivesClampedArgs() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        ArgClamper clamper = (name, raw) -> "{\"replicas\":2}";

        guard(tool, clamper, autoApprove()).call("{\"replicas\":0}");

        assertThat(tool.receivedArgs).containsExactly("{\"replicas\":2}");
    }

    @Test
    @DisplayName("③ 发生夹紧时留下审计——这通常意味着模型生成了越界参数")
    void clampingIsAudited() {
        ArgClamper clamper = (name, raw) -> "{\"replicas\":2}";

        guard(RecordingTool.ok(TOOL, "scaled"), clamper, autoApprove()).call("{\"replicas\":0}");

        assertThat(audit.countOf(GateOutcome.CLAMPED)).isEqualTo(1);
        ToolAuditEvent ev = audit.last(GateOutcome.CLAMPED);
        // args_masked 放越界的原始参数，result_masked 放夹紧后的——两者都已过脱敏
        assertThat(ev.argsMasked()).isEqualTo("{\"replicas\":0}");
        assertThat(ev.resultMasked()).isEqualTo("{\"replicas\":2}");
        assertThat(ev.toolSource()).isEqualTo(ToolSource.LOCAL);
        assertThat(ev.riskLevel()).isEqualTo(RiskLevel.HIGH);
        assertThat(ev.context().traceId()).isEqualTo("trace-gtc");
    }

    @Test
    @DisplayName("③ 未发生夹紧时不产生夹紧审计，避免噪音")
    void noClampNoAudit() {
        guard(RecordingTool.ok(TOOL, "scaled"), ArgClamper.NOOP, autoApprove())
                .call("{\"replicas\":2}");

        assertThat(audit.countOf(GateOutcome.CLAMPED)).isZero();
    }

    @Test
    @DisplayName("③ 审批人看到的必须是夹紧后的参数——否则批的不是真正会执行的东西")
    void approverSeesClampedArgs() {
        RecordingGate gate = new RecordingGate(Approval.granted("alice"));
        ArgClamper clamper = (name, raw) -> "{\"replicas\":2}";

        guard(RecordingTool.ok(TOOL, "scaled"), clamper, gate).call("{\"replicas\":999}");

        assertThat(gate.lastArgs).isEqualTo("{\"replicas\":2}");
    }

    // ---------------------------------------------------------- ④ 幂等

    @Test
    @DisplayName("④ 同一幂等键重复调用只执行一次，第二次直接返回上次结果")
    void replayDoesNotReExecute() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled-to-3");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        String first = guarded.call("{\"replicas\":3}");
        String second = guarded.call("{\"replicas\":3}");

        assertThat(first).isEqualTo("scaled-to-3");
        assertThat(second).isEqualTo("scaled-to-3");
        assertThat(tool.calls).as("重试不能导致二次扩容").isEqualTo(1);
    }

    @Test
    @DisplayName("④ 参数字面量不同但语义相同时也算同一次调用（规范化生效）")
    void whitespaceDifferenceDoesNotBreakIdempotency() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled-to-3");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        guarded.call("{\"replicas\": 3}");
        guarded.call("{\"replicas\":3}");

        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("★ ④ 键顺序不同也算同一次调用——这条曾经是真的会二次执行")
    void keyOrderDoesNotBreakIdempotency() {
        // 【这是一条回归断言，不是一个新特性】
        // canonical() 的 javadoc 一直写着「JSON key 排序 + 去空白」，
        // 而实现只有 replaceAll("\\s+", "")——**根本没有排序**。
        // 于是这两个字面量算出两个幂等键，语义相同的两次请求被判成两次操作，
        // 而幂等失效的后果正是二次扩容、二次重启。
        // 旧实现下这条断言会失败（tool.calls == 2）。
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled-to-3");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        guarded.call("{\"service\":\"order\",\"replicas\":3}");
        guarded.call("{\"replicas\":3,\"service\":\"order\"}");

        assertThat(tool.calls).as("键顺序不同不能导致二次扩容").isEqualTo(1);
    }

    @Test
    @DisplayName("④ 数字写法不同也算同一次调用：8 与 8.0 是同一个扩容请求")
    void numberNotationDoesNotBreakIdempotency() {
        // 模型重试时完全可能把 8 写成 8.0，那是同一个请求。
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled-to-8");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        guarded.call("{\"replicas\":8}");
        guarded.call("{\"replicas\":8.0}");

        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("④ 嵌套对象的键顺序也归一，但数组顺序不能归一——数组顺序是有语义的")
    void nestedKeysAreCanonicalizedButArrayOrderIsNot() {
        RecordingTool tool = RecordingTool.ok(TOOL, "ok");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        guarded.call("{\"scale\":{\"max\":10,\"min\":1},\"replicas\":8}");
        guarded.call("{\"replicas\":8,\"scale\":{\"min\":1,\"max\":10}}");
        assertThat(tool.calls).as("嵌套键顺序不同仍是同一次调用").isEqualTo(1);

        // 反过来：[1,2] 与 [2,1] 是不同的参数，绝不能被幂等吞掉
        guarded.call("{\"targets\":[\"a\",\"b\"]}");
        guarded.call("{\"targets\":[\"b\",\"a\"]}");
        assertThat(tool.calls).as("数组顺序不同是不同操作，必须各执行一次").isEqualTo(3);
    }

    @Test
    @DisplayName("④ 非法 JSON 不能让网关炸掉：退回字面量比较，至少保证确定性")
    void malformedArgsDoNotBreakTheGateway() {
        RecordingTool tool = RecordingTool.ok(TOOL, "ok");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        assertThat(guarded.call("{not json")).isEqualTo("ok");
        // 同一个非法字面量重复调用仍然幂等：退回「去空白」后，
        // 字面量相同 ⇒ 键相同，所以第二次是重放而不是再执行一次。
        assertThat(guarded.call("{not json")).isEqualTo("ok");
        assertThat(tool.calls).as("非法 JSON 也要能重放，否则重试就真的再执行一遍").isEqualTo(1);
    }

    @Test
    @DisplayName("④ 不同步序号视为不同调用，不会被幂等误吞")
    void differentStepIsNotSwallowed() {
        RecordingTool tool = RecordingTool.ok(TOOL, "ok");

        guardStep(tool, 1).call("{\"replicas\":3}");
        guardStep(tool, 2).call("{\"replicas\":3}");

        assertThat(tool.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("④ 执行权被抢走且尚无结果时必须失败，绝不并发执行同一个写操作")
    void inFlightDuplicateFailsInsteadOfDoubleExecuting() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());
        // 模拟另一个实例已经抢到执行权但还没跑完
        String key = idempotency.keyFor("run-1", 1, TOOL,
                GuardedToolCallback.canonical("{\"replicas\":3}"));
        assertThat(ledger.claim(key, TOOL)).isTrue();

        // 二次扩容、二次重启是会出真事故的；"这次调用失败了"只是重试一次。
        // 两者相权，必须选后者。
        assertThatThrownBy(() -> guarded.call("{\"replicas\":3}"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("duplicate in-flight");
        assertThat(tool.calls).as("绝不能真的执行").isZero();
    }

    @Test
    @DisplayName("④ 并发调用同一个幂等键，工具只被真正执行一次")
    void concurrentCallsExecuteExactlyOnce() throws Exception {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());
        int threads = 16;
        java.util.concurrent.CyclicBarrier gate = new java.util.concurrent.CyclicBarrier(threads);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        try {
            java.util.List<java.util.concurrent.Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    gate.await();   // 让所有线程尽量同时冲进 claim
                    return guarded.call("{\"replicas\":3}");
                }));
            }
            int replayed = 0;
            int denied = 0;
            for (var f : futures) {
                try {
                    assertThat(f.get()).isEqualTo("scaled");
                    replayed++;
                } catch (java.util.concurrent.ExecutionException expected) {
                    // 抢不到执行权、且此时还没有可重放结果的线程会拿到 ToolDeniedException
                    assertThat(expected.getCause()).isInstanceOf(ToolDeniedException.class);
                    denied++;
                }
            }
            // 【不要在这里断言 replayed == 1】
            // 这条断言我第一版就是这么写的，CI 上炸成 "expected: 1 but was: 16"。
            // 原因是：抢不到执行权的线程会走 resultOf()，如果赢家此刻已经 complete()，
            // 它们拿到的就是上次的结果并原样返回 —— 这是**期望行为**（重放），不是缺陷。
            // 于是 replayed 落在 1..threads 之间，取决于赢家什么时候写完结果，
            // 断言它等于 1 等于在断言一个时序，必然随机红。
            // 真正确定的只有两件事，分别断言：
            assertThat(replayed + denied).as("每个线程必须落在两种确定状态之一").isEqualTo(threads);
            assertThat(replayed).as("至少有一个线程拿到结果").isPositive();
        } finally {
            pool.shutdownNow();
        }
        // 这一条才是承重的：并发重试不能导致二次扩容。
        assertThat(tool.calls).as("16 个线程同时进来，工具只能被真正执行一次").isEqualTo(1);
    }

    @Test
    @DisplayName("④ 执行失败会释放执行权，同一个请求可以重试")
    void failureReleasesTheClaimSoRetryIsPossible() {
        RecordingTool tool = RecordingTool.failing(TOOL, new IllegalStateException("boom"));
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        assertThatThrownBy(() -> guarded.call("{\"replicas\":3}"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(ledger.size()).as("失败后必须释放，否则这个幂等键就废了").isZero();
    }

    @Test
    @DisplayName("④ 审批被拒也会释放执行权——换个审批人应该能重新发起")
    void rejectedApprovalReleasesTheClaim() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP,
                new RecordingGate(Approval.rejected("alice", "现在不行")));

        guarded.call("{\"replicas\":3}");

        assertThat(tool.calls).isZero();
        assertThat(ledger.size()).as("审批被拒不等于执行过了").isZero();
    }

    @Test
    @DisplayName("④ 规范化只去空白，不改变语义")
    void canonicalStripsWhitespaceOnly() {
        assertThat(GuardedToolCallback.canonical("{ \"a\" : 1 , \"b\" : 2 }"))
                .isEqualTo("{\"a\":1,\"b\":2}");
        assertThat(GuardedToolCallback.canonical(null)).isEmpty();
    }

    // ---------------------------------------------------------- ⑤ 审批闸门

    @Test
    @DisplayName("⑤ 审批被拒时不执行，并把拒绝原因返回给模型让它改走别的路径")
    void rejectedApprovalBlocksExecutionAndReturnsHint() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        RecordingGate gate = new RecordingGate(Approval.rejected("alice", "变更窗口内禁止扩容"));

        String result = guard(tool, ArgClamper.NOOP, gate).call("{\"replicas\":5}");

        assertThat(tool.calls).isZero();
        assertThat(result).contains("\"denied\":true").contains("变更窗口内禁止扩容").contains("hint");
        // 审批被拒 = 关卡结论 DENIED。审批本身（谁申请、谁批）属于 approval_record，
        // 所以这里不再有一条独立的 APPROVAL 事件——同一个事实不该存两份。
        assertThat(audit.countOf(GateOutcome.DENIED)).isEqualTo(1);
        assertThat(audit.last(GateOutcome.DENIED).deniedReason()).isEqualTo("变更窗口内禁止扩容");
        assertThat(audit.countOf(GateOutcome.PASSED)).as("被拒就不该有放行记录").isZero();
    }

    @Test
    @DisplayName("⑤ 审批超时不是卡死：expired 标记回传给模型，用于触发升级")
    void expiredApprovalIsSignalledNotStuck() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        RecordingGate gate = new RecordingGate(Approval.timedOut());

        String result = guard(tool, ArgClamper.NOOP, gate).call("{\"replicas\":5}");

        assertThat(tool.calls).isZero();
        assertThat(result).contains("\"denied\":true").contains("\"expired\":true");
    }

    @Test
    @DisplayName("⑤ 审批通过后正常执行")
    void approvedExecutionProceeds() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        RecordingGate gate = new RecordingGate(Approval.granted("alice"));

        assertThat(guard(tool, ArgClamper.NOOP, gate).call("{\"replicas\":5}")).isEqualTo("scaled");
        assertThat(gate.calls).isEqualTo(1);
        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("⑤ 只读工具不走审批闸门")
    void readOnlyToolSkipsApproval() {
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "metrics");
        RecordingGate gate = new RecordingGate(Approval.granted("alice"));

        guard(tool, ArgClamper.NOOP, gate).call("{}");

        assertThat(gate.calls).isZero();
        assertThat(tool.calls).isEqualTo(1);
    }

    // ---------------------------------------------------------- ⑥⑦ 执行与审计

    @Test
    @DisplayName("⑥⑦ 成功执行后落审计，且结果可被后续重放取回")
    void successIsAudited() {
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "cpu=95%");

        guard(tool, ArgClamper.NOOP, autoApprove()).call("{\"service\":\"order\"}");

        assertThat(audit.countOf(GateOutcome.PASSED)).isEqualTo(1);
        ToolAuditEvent ev = audit.last(GateOutcome.PASSED);
        assertThat(ev.resultMasked()).isEqualTo("cpu=95%");
        assertThat(ev.argsMasked()).isEqualTo("{\"service\":\"order\"}");
        assertThat(ev.durationMs()).as("审计要能统计工具耗时").isNotNull();
        assertThat(ledger.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("⑥⑦ 执行失败也落审计，且异常继续抛出（不能被吞掉）")
    void failureIsAuditedAndRethrown() {
        RuntimeException boom = new IllegalStateException("下游超时");
        RecordingTool tool = RecordingTool.failing(TOOL, boom);

        assertThatThrownBy(() -> guard(tool, ArgClamper.NOOP, autoApprove()).call("{\"replicas\":3}"))
                .isSameAs(boom);

        // 执行失败不是关卡拦截，所以 gate_outcome 仍是 PASSED（见 GateOutcome 说明），
        // 失败这个事实记在 result_masked 里。
        assertThat(audit.countOf(GateOutcome.PASSED)).isEqualTo(1);
        assertThat(audit.last(GateOutcome.PASSED).resultMasked())
                .contains("IllegalStateException").contains("下游超时");
        assertThat(ledger.size()).as("失败的执行不能被当成可重放的成功结果").isZero();
    }

    // ---------------------------------------------------------- 关卡顺序

    @Test
    @DisplayName("顺序：① 默认拒绝在 ② kill switch 之前——不泄露未注册工具的存在性")
    void defaultDenyRunsBeforeKillSwitch() {
        killSwitch.set(RunMode.OFF);

        assertThatThrownBy(() -> guard(RecordingTool.ok("rogue", "x"), ArgClamper.NOOP, autoApprove()).call("{}"))
                .isInstanceOf(ToolDeniedException.class)
                .hasMessageContaining("not in allowlist")
                .hasMessageNotContaining("mode=OFF");
    }

    @Test
    @DisplayName("顺序：② kill switch 在 ③ 参数夹紧之前——被禁的工具不该留下夹紧审计")
    void killSwitchRunsBeforeClamping() {
        killSwitch.set(RunMode.OFF);
        RecordingClamper clamper = new RecordingClamper("{\"replicas\":2}");

        assertThatThrownBy(() -> guard(RecordingTool.ok(TOOL, "x"), clamper, autoApprove()).call("{}"))
                .isInstanceOf(ToolDeniedException.class);

        assertThat(clamper.calls).as("被禁工具不应触发夹紧").isZero();
        assertThat(audit.countOf(GateOutcome.CLAMPED)).isZero();
        // kill switch 拦下必须留痕——这是原先缺失的一条：
        // 所有审计调用点都写在放行之后，于是最该记录的安全事件反而没被记录。
        assertThat(audit.countOf(GateOutcome.DENIED)).as("kill switch 拦下必须落审计").isEqualTo(1);
        // 这个用例把模式设成 OFF，所以文案是「agent disabled」而不是
        // READ_ONLY 分支的「write tool blocked」——两条分支要能被审计区分出来。
        assertThat(audit.last(GateOutcome.DENIED).deniedReason())
                .isEqualTo("agent disabled: mode=OFF");
        assertThat(audit.last(GateOutcome.DENIED).riskLevel())
                .as("被 kill switch 拦下的这次调用，风险级要照实记")
                .isEqualTo(RiskLevel.HIGH);
    }

    @Test
    @DisplayName("顺序：④ 幂等在 ⑤ 审批之前——重放不该再去打扰审批人")
    void idempotencyRunsBeforeApproval() {
        RecordingTool tool = RecordingTool.ok(TOOL, "scaled");
        RecordingGate gate = new RecordingGate(Approval.granted("alice"));
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, gate);

        guarded.call("{\"replicas\":3}");
        guarded.call("{\"replicas\":3}");

        assertThat(gate.calls).as("重放不应二次请求审批").isEqualTo(1);
    }

    // ---------------------------------------------------------- 便捷构造与透传

    @Test
    @DisplayName("readOnly 便捷构造：自动放行、不夹紧，适用于只读工具")
    void readOnlyFactoryWiresPermissiveDefaults() {
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "metrics");

        GuardedToolCallback guarded = GuardedToolCallback.readOnly(
                tool, policyEngine, killSwitch, audit, CTX, idempotency, ledger, "run-1", 1);

        assertThat(guarded.call("{}")).isEqualTo("metrics");
        assertThat(tool.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("工具定义原样透传——装饰器不能改变模型看到的工具签名")
    void toolDefinitionIsPassedThrough() {
        RecordingTool tool = RecordingTool.ok(READ_TOOL, "metrics");
        GuardedToolCallback guarded = guard(tool, ArgClamper.NOOP, autoApprove());

        assertThat(guarded.getToolDefinition().name()).isEqualTo(READ_TOOL);
        assertThat(guarded.getToolMetadata()).isNotNull();
        assertThat(guarded.delegate()).isSameAs(tool);
    }

    // ------------------------------------------------------------------ helpers

    private GuardedToolCallback guard(ToolCallback delegate, ArgClamper clamper, ApprovalGate gate) {
        return new GuardedToolCallback(delegate, policyEngine, killSwitch, gate,
                audit, CTX, idempotency, ledger, clamper, "run-1", 1);
    }

    private GuardedToolCallback guardStep(ToolCallback delegate, int step) {
        return new GuardedToolCallback(delegate, policyEngine, killSwitch, autoApprove(),
                audit, CTX, idempotency, ledger, ArgClamper.NOOP, "run-1", step);
    }

    private static ApprovalGate autoApprove() {
        return (key, policy, args, ctx) -> Approval.granted("auto");
    }

    /** 记录被调用情况的假工具。 */
    private static final class RecordingTool implements ToolCallback {
        private final String name;
        private final String result;
        private final RuntimeException failure;
        final List<String> receivedArgs = new ArrayList<>();
        int calls;

        private RecordingTool(String name, String result, RuntimeException failure) {
            this.name = name;
            this.result = result;
            this.failure = failure;
        }

        static RecordingTool ok(String name, String result) {
            return new RecordingTool(name, result, null);
        }

        static RecordingTool failing(String name, RuntimeException failure) {
            return new RecordingTool(name, null, failure);
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description("测试工具 " + name)
                    .inputSchema("{\"type\":\"object\"}")
                    .build();
        }

        @Override
        public String call(String toolInput) {
            calls++;
            receivedArgs.add(toolInput);
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    /**
     * 记录全部审计事件的假审计日志。
     *
     * <p>刻意存 {@link ToolAuditEvent} 而不是格式化字符串：
     * 断言写在真实类型上，才能顺带验到关卡结论、来源与风险级——
     * 而这些正是原先那 5 个 {@code recordXxx} 方法给不出来、
     * 导致 {@code JdbcToolAuditLog} 一直写不出来的字段。
     */
    private static final class RecordingAudit implements ToolAuditLog {
        final List<ToolAuditEvent> events = new ArrayList<>();

        @Override
        public void record(ToolAuditEvent event) {
            events.add(event);
        }

        long countOf(GateOutcome outcome) {
            return events.stream().filter(e -> e.gateOutcome() == outcome).count();
        }

        ToolAuditEvent last(GateOutcome outcome) {
            for (int i = events.size() - 1; i >= 0; i--) {
                if (events.get(i).gateOutcome() == outcome) {
                    return events.get(i);
                }
            }
            return null;
        }
    }

    /** 固定返回某个审批结果的假闸门。 */
    private static final class RecordingGate implements ApprovalGate {
        private final Approval result;
        int calls;
        String lastArgs;
        ToolAuditContext lastContext;

        RecordingGate(Approval result) {
            this.result = result;
        }

        @Override
        public Approval await(String idempotencyKey, ToolPolicy policy, String args,
                              ToolAuditContext context) {
            calls++;
            lastArgs = args;
            lastContext = context;
            return result;
        }
    }

    /** 记录调用次数的假夹紧器。 */
    private static final class RecordingClamper implements ArgClamper {
        private final String clamped;
        int calls;

        RecordingClamper(String clamped) {
            this.clamped = clamped;
        }

        @Override
        public String clamp(String toolName, String rawArgs) {
            calls++;
            return clamped;
        }
    }
}
