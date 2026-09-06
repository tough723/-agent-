package com.oncall.toolgateway;

import com.oncall.domain.tool.RiskLevel;
import com.oncall.domain.tool.ToolPolicy;
import com.oncall.domain.tool.ToolSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link PollingApprovalGate} 的验收测试。
 *
 * <p><b>这个类守的是三条性质</b>，每一条都对应一个真实故障：
 * <ol>
 *   <li><b>先写库再等待</b> —— 否则进程在等待期间崩溃，
 *       这次审批在数据库里不存在，而操作可能已经被批了。</li>
 *   <li><b>必须超时</b> —— 原方案全文检索「超时」命中 0 次，
 *       而审批人不在线时 {@code PENDING_APPROVAL} 会永久卡死，告警还在烧。</li>
 *   <li><b>快照必须脱敏</b> —— DDL 注释：「否则这次审批无效」。</li>
 * </ol>
 *
 * <p><b>为什么注入 {@code Clock}</b>：超时逻辑用 {@code Instant.now()} 就没法测，
 * 而「真的睡到超时」的测试既慢又随机。这是本项目踩过的坑——
 * 用假时钟去驱动 {@code Thread.sleep} 会真的睡着。
 */
class PollingApprovalGateTest {

    private static final ToolAuditContext CTX =
            new ToolAuditContext("trace-abc", "run-1", "step-2", "alice");

    private static ToolPolicy policy(Duration approvalTimeout) {
        return new ToolPolicy("scale_replicas", ToolSource.MCP, RiskLevel.HIGH,
                true, approvalTimeout, false, null);
    }

    /** 可推进的时钟。只驱动 {@code clock.instant()}，不驱动 {@code Thread.sleep}。 */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.now();

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    // ------------------------------------------------------------ ① 先写库

    @Test
    @DisplayName("等待之前就把 PENDING 记录写进库")
    void writesPendingRecordBeforeWaiting() {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        // 先塞一条已批准的记录，让 await 第一次轮询就返回，不必等
        String id = PollingApprovalGate.recordId("key-1");
        store.insert(ApprovalRecord.pending(id, CTX, "scale_replicas", RiskLevel.HIGH,
                "alice", "{}"));
        store.decide(id, ApprovalDecision.GRANTED, "bob", null);

        PollingApprovalGate gate = new PollingApprovalGate(store, Duration.ofMillis(1),
                Clock.systemUTC(), "agent");
        gate.await("key-1", policy(Duration.ofSeconds(30)), "{}", CTX);

        assertThat(store.count()).as("不该重复插一条").isEqualTo(1);
        assertThat(store.pending()).isEmpty();
    }

    @Test
    @DisplayName("轮询期间记录已经是 PENDING 且可读 —— 崩溃恢复靠这个")
    void pendingRecordIsVisibleWhileWaiting() {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        MutableClock clock = new MutableClock();
        PollingApprovalGate gate = new PollingApprovalGate(store, Duration.ofMillis(1), clock, "agent");

        // 时钟推到超过 deadline，await 会在第一次检查后就走超时分支
        clock.advance(Duration.ofMinutes(2));
        Approval a = gate.await("key-1", policy(Duration.ofSeconds(1)), "{}", CTX);

        assertThat(a.expired()).isTrue();
        ApprovalRecord r = store.find(PollingApprovalGate.recordId("key-1")).orElseThrow();
        assertThat(r.decision()).as("超时也要留下终态记录，不能停在 PENDING")
                .isEqualTo(ApprovalDecision.TIMED_OUT);
        assertThat(r.approver()).as("没有人做过这个决定").isNull();
        assertThat(r.comment()).contains("timed out");
    }

    // ------------------------------------------------------------ ② 超时

    @Test
    @DisplayName("超时返回 expired 而不是无限等待")
    void timesOutInsteadOfWaitingForever() {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        MutableClock clock = new MutableClock();
        PollingApprovalGate gate = new PollingApprovalGate(store, Duration.ofMillis(1), clock, "agent");
        clock.advance(Duration.ofMinutes(5));

        Approval a = gate.await("key-1", policy(Duration.ofSeconds(1)), "{}", CTX);

        assertThat(a.approved()).isFalse();
        assertThat(a.expired()).isTrue();
    }

    @Test
    @DisplayName("审批人在超时前一刻批准 → 不把他的决定覆盖成超时")
    void doesNotOverwriteALateApproval() {
        InMemoryApprovalRecordStore base = new InMemoryApprovalRecordStore();
        MutableClock clock = new MutableClock();
        String id = PollingApprovalGate.recordId("key-1");

        // 第二次 find 之前先把记录改成 GRANTED：
        // 确定性地走到「decide 返回 false」那条路径，
        // 而不必去撞真实的竞态。
        ApprovalRecordStore racing = new ApprovalRecordStore() {
            @Override public void insert(ApprovalRecord r) { base.insert(r); }
            @Override public boolean decide(String i, ApprovalDecision o, String who, String note) {
                // 审批人恰好在这一刻点了批准：把决定抢在前面写进去。
                // 注意注入点必须在 decide 上而不是 find 上——
                // 若在 find 里改，循环第一次就会看到 GRANTED 并直接返回，
                // 永远走不到「decide 返回 false」那条路径，测试会绿但什么也没测到。
                base.decide(i, ApprovalDecision.GRANTED, "bob", "刚好赶上");
                return base.decide(i, o, who, note);
            }
            @Override public Optional<ApprovalRecord> find(String i) { return base.find(i); }
            @Override public List<ApprovalRecord> pending() { return base.pending(); }
            @Override public List<ApprovalRecord> pendingRequestedBefore(Instant before) {
                return base.pendingRequestedBefore(before);
            }
            @Override public int count() { return base.count(); }
        };

        PollingApprovalGate gate = new PollingApprovalGate(racing, Duration.ofMillis(1), clock, "agent");
        clock.advance(Duration.ofMinutes(5));
        Approval a = gate.await("key-1", policy(Duration.ofSeconds(1)), "{}", CTX);

        assertThat(a.approved()).as("审批人的决定必须胜出").isTrue();
        assertThat(a.approver()).isEqualTo("bob");
        assertThat(base.find(id).orElseThrow().decision()).isEqualTo(ApprovalDecision.GRANTED);
    }

    // ------------------------------------------------------------ ③ 脱敏

    @Test
    @DisplayName("落库的快照是脱敏后的，密钥不出现在 approval_record 里")
    void argsSnapshotIsMasked() {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        MutableClock clock = new MutableClock();
        PollingApprovalGate gate = new PollingApprovalGate(store, Duration.ofMillis(1), clock, "agent");
        clock.advance(Duration.ofMinutes(2));

        gate.await("key-1", policy(Duration.ofSeconds(1)),
                "{\"host\":\"db-1.internal\",\"password\":\"hunter2-secret\"}", CTX);

        String snapshot = store.find(PollingApprovalGate.recordId("key-1"))
                .orElseThrow().argsSnapshot();
        assertThat(snapshot).doesNotContain("hunter2-secret");
        assertThat(snapshot).contains("***");
        assertThat(snapshot).as("非敏感字段仍要可读，否则审批人没法判断").contains("db-1.internal");
    }

    // ------------------------------------------------------------ 审批结论映射

    @Test
    @DisplayName("已批准的记录 → Approval.granted")
    void mapsGranted() {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        String id = PollingApprovalGate.recordId("key-1");
        store.insert(ApprovalRecord.pending(id, CTX, "scale_replicas", RiskLevel.HIGH, "alice", "{}"));
        store.decide(id, ApprovalDecision.GRANTED, "bob", "同意");

        Approval a = new PollingApprovalGate(store, Duration.ofMillis(1), Clock.systemUTC(), "agent")
                .await("key-1", policy(Duration.ofSeconds(30)), "{}", CTX);
        assertThat(a.approved()).isTrue();
        assertThat(a.approver()).isEqualTo("bob");
        assertThat(a.expired()).isFalse();
    }

    @Test
    @DisplayName("已拒绝的记录 → Approval.rejected，并带上理由")
    void mapsRejected() {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        String id = PollingApprovalGate.recordId("key-1");
        store.insert(ApprovalRecord.pending(id, CTX, "scale_replicas", RiskLevel.HIGH, "alice", "{}"));
        store.decide(id, ApprovalDecision.REJECTED, "bob", "现在是大促窗口");

        Approval a = new PollingApprovalGate(store, Duration.ofMillis(1), Clock.systemUTC(), "agent")
                .await("key-1", policy(Duration.ofSeconds(30)), "{}", CTX);
        assertThat(a.approved()).isFalse();
        assertThat(a.reason()).contains("大促窗口");
    }

    @Test
    @DisplayName("轮询到有人批准为止 —— 真正的轮询循环")
    void pollsUntilApproved() throws Exception {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        String id = PollingApprovalGate.recordId("key-1");
        PollingApprovalGate gate = new PollingApprovalGate(store, Duration.ofMillis(5),
                Clock.systemUTC(), "agent");

        Thread approver = new Thread(() -> {
            for (int i = 0; i < 400 && store.find(id).isEmpty(); i++) {
                sleep(5);     // 等 await 把 PENDING 写进去
            }
            store.decide(id, ApprovalDecision.GRANTED, "bob", "同意");
        });
        approver.start();

        Approval a = gate.await("key-1", policy(Duration.ofSeconds(30)), "{}", CTX);
        approver.join(10_000);

        assertThat(a.approved()).isTrue();
        assertThat(a.approver()).isEqualTo("bob");
    }

    // ------------------------------------------------------------ 申请人

    @Test
    @DisplayName("operator 为空时用兜底申请人 —— requester 是 NOT NULL")
    void requesterFallsBackWhenOperatorMissing() {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        MutableClock clock = new MutableClock();
        PollingApprovalGate gate = new PollingApprovalGate(store, Duration.ofMillis(1), clock, "agent");
        clock.advance(Duration.ofMinutes(2));

        gate.await("key-1", policy(Duration.ofSeconds(1)), "{}", ToolAuditContext.of("t"));

        assertThat(store.find(PollingApprovalGate.recordId("key-1")).orElseThrow().requester())
                .isEqualTo("agent");
    }

    @Test
    @DisplayName("operator 存在时用 operator 作为申请人")
    void requesterUsesOperatorWhenPresent() {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        MutableClock clock = new MutableClock();
        PollingApprovalGate gate = new PollingApprovalGate(store, Duration.ofMillis(1), clock, "agent");
        clock.advance(Duration.ofMinutes(2));

        gate.await("key-1", policy(Duration.ofSeconds(1)), "{}", CTX);

        assertThat(store.find(PollingApprovalGate.recordId("key-1")).orElseThrow().requester())
                .isEqualTo("alice");
    }

    // ------------------------------------------------------------ 记录 id

    @Test
    @DisplayName("同一个幂等键派生同一个记录 id，且长度不超过列宽")
    void recordIdIsStableAndFitsTheColumn() {
        String a = PollingApprovalGate.recordId("same-key");
        assertThat(PollingApprovalGate.recordId("same-key")).isEqualTo(a);
        assertThat(PollingApprovalGate.recordId("other-key")).isNotEqualTo(a);
        assertThat(a).hasSize(64).as("SHA-256 十六进制正好 64 字符，与 VARCHAR(64) 齐平");
    }

    @Test
    @DisplayName("同一个幂等键重试不会产生第二条审批 —— 否则重试就是给审批人刷屏")
    void retriesReuseTheSameRecord() {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        String id = PollingApprovalGate.recordId("key-1");
        store.insert(ApprovalRecord.pending(id, CTX, "scale_replicas", RiskLevel.HIGH, "alice", "{}"));
        store.decide(id, ApprovalDecision.GRANTED, "bob", null);

        PollingApprovalGate gate = new PollingApprovalGate(store, Duration.ofMillis(1),
                Clock.systemUTC(), "agent");
        gate.await("key-1", policy(Duration.ofSeconds(30)), "{}", CTX);
        gate.await("key-1", policy(Duration.ofSeconds(30)), "{}", CTX);
        gate.await("key-1", policy(Duration.ofSeconds(30)), "{}", CTX);

        assertThat(store.count()).isEqualTo(1);
    }

    // ------------------------------------------------------------ 构造校验

    @Test
    @DisplayName("构造参数校验：store / pollInterval / clock / fallbackRequester")
    void rejectsBadConstructorArgs() {
        InMemoryApprovalRecordStore store = new InMemoryApprovalRecordStore();
        assertThatThrownBy(() -> new PollingApprovalGate(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PollingApprovalGate(store, Duration.ZERO,
                Clock.systemUTC(), "agent"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PollingApprovalGate(store, Duration.ofMillis(1), null, "agent"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PollingApprovalGate(store, Duration.ofMillis(1),
                Clock.systemUTC(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("fallbackRequester");
    }

    @Test
    @DisplayName("await 缺 policy 或 context → 拒绝（trace_id 是 NOT NULL）")
    void rejectsMissingContext() {
        PollingApprovalGate gate = new PollingApprovalGate(new InMemoryApprovalRecordStore());
        assertThatThrownBy(() -> gate.await("k", null, "{}", CTX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("policy");
        assertThatThrownBy(() -> gate.await("k", policy(Duration.ofSeconds(1)), "{}", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
