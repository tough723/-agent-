package com.oncall.toolgateway;

import com.oncall.domain.tool.ToolPolicy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 轮询式审批闸门 —— {@link ApprovalGate} 的第一个生产实现。
 *
 * <p><b>为什么先做轮询而不是企微卡片</b>：本类之前 {@code ApprovalGate}
 * 在生产代码里<b>一个实现都没有</b>——接口、javadoc、契约都写好了，
 * 但没有任何东西真的会拦住一次高危操作。
 * 先把「审批」这件事在数据层做实（写记录、等结论、超时升级），
 * 通知渠道再作为<b>可替换的一层</b>叠上去：
 * 事实来源是数据库，企微/钉钉只是催人的手段。
 * 反过来做（把闸门写成企微客户端）会让审批的正确性依赖一个外部服务的可用性，
 * 而企微不可达时高危操作就该被放行吗？不该。
 *
 * <h2>三条不可退让的性质</h2>
 * <ol>
 *   <li><b>先写库再等待。</b>否则进程在等待期间崩溃，
 *       这次审批在数据库里就不存在，而操作可能已经被批了。</li>
 *   <li><b>必须超时。</b>原方案全文检索「超时」命中 0 次，
 *       而 {@code PENDING_APPROVAL} 一旦审批人不在线就会永久卡死，告警还在烧。
 *       超时不是「没有结论」，它是一个必须触发升级的结论。</li>
 *   <li><b>快照必须脱敏。</b>{@code approval_record.args_snapshot} 的 DDL 注释写着
 *       「必须是夹紧后且脱敏后的值；否则这次审批无效」——
 *       审批人看到密钥不是「多看到一点」，是这次审批本身不成立。</li>
 * </ol>
 */
public final class PollingApprovalGate implements ApprovalGate {

    /** 没有 operator 时的申请人。Agent 自主执行时 operator 本来就是 null。 */
    public static final String AGENT_REQUESTER = "agent";

    private static final String TIMEOUT_COMMENT = "approval timed out";

    private final ApprovalRecordStore store;
    private final Duration pollInterval;
    private final Clock clock;
    private final String fallbackRequester;

    public PollingApprovalGate(ApprovalRecordStore store) {
        this(store, Duration.ofSeconds(2), Clock.systemUTC(), AGENT_REQUESTER);
    }

    /**
     * @param store             审批记录存储；生产必须是 {@link JdbcApprovalRecordStore}
     * @param pollInterval      轮询间隔。太短压数据库，太长让审批延迟变大
     * @param clock             时钟。<b>刻意可注入</b>：超时逻辑用 {@code Instant.now()}
     *                          就没法测，而"sleep 到超时"的测试既慢又随机
     * @param fallbackRequester operator 为空时用的申请人；{@code requester} 是 NOT NULL
     */
    public PollingApprovalGate(ApprovalRecordStore store, Duration pollInterval,
                               Clock clock, String fallbackRequester) {
        if (store == null) {
            throw new IllegalArgumentException("store 不能为 null");
        }
        if (pollInterval == null || pollInterval.isZero() || pollInterval.isNegative()) {
            throw new IllegalArgumentException("pollInterval 必须为正，收到：" + pollInterval);
        }
        if (clock == null) {
            throw new IllegalArgumentException("clock 不能为 null");
        }
        if (fallbackRequester == null || fallbackRequester.isBlank()) {
            throw new IllegalArgumentException(
                    "fallbackRequester 不能为空：requester 是 NOT NULL 列");
        }
        this.store = store;
        this.pollInterval = pollInterval;
        this.clock = clock;
        this.fallbackRequester = fallbackRequester.trim();
    }

    @Override
    public Approval await(String idempotencyKey, ToolPolicy policy, String args,
                          ToolAuditContext context) {
        if (policy == null) {
            throw new IllegalArgumentException("policy 不能为 null");
        }
        if (context == null) {
            throw new IllegalArgumentException(
                    "context 不能为 null：approval_record.trace_id 是 NOT NULL");
        }
        String id = recordId(idempotencyKey);
        String requester = context.operator() != null ? context.operator() : fallbackRequester;

        // ① 先写库。已存在则复用——同一个幂等键的重试不该再发一次审批，
        //    否则重试会变成给审批人刷屏，而那是让人直接关掉通知的最快方式。
        Optional<ApprovalRecord> existing = store.find(id);
        if (existing.isEmpty()) {
            store.insert(ApprovalRecord.pending(id, context, policy.toolName(), policy.risk(),
                    requester, ArgMasker.mask(args)));
        }

        // ② 等到有结论或超时
        Instant deadline = store.find(id).orElseThrow().requestedAt()
                .plus(timeoutOf(policy));
        while (true) {
            ApprovalRecord current = store.find(id).orElseThrow();
            if (current.decision().isFinal()) {
                return toApproval(current);
            }
            if (!clock.instant().isBefore(deadline)) {
                break;
            }
            sleep();
        }

        // ③ 超时：把结论写成 TIMED_OUT，而不是留一个永远 PENDING 的行。
        //    留着 PENDING 的话，「现在有哪些操作卡着」这个查询会永远包含它，
        //    而它其实早就该升级了。
        //    decide 是条件更新：如果审批人恰好在这一刻点了批准，
        //    我们不会把他的决定覆盖成超时。
        if (store.decide(id, ApprovalDecision.TIMED_OUT, null, TIMEOUT_COMMENT)) {
            return Approval.timedOut();
        }
        return toApproval(store.find(id).orElseThrow());
    }

    /**
     * 审批记录 id。
     *
     * <p><b>由幂等键派生而不是随机 UUID</b>：这样同一个工具调用的重试
     * 会命中同一条审批记录，而不是每次重试都新建一条。
     * SHA-256 十六进制正好 64 字符，与 {@code approval_record.id VARCHAR(64)} 齐平。
     */
    static String recordId(String idempotencyKey) {
        return Sha256IdempotencyStore.sha256("approval|" + (idempotencyKey == null
                ? UUID.randomUUID().toString() : idempotencyKey));
    }

    private static Approval toApproval(ApprovalRecord r) {
        return switch (r.decision()) {
            case GRANTED -> Approval.granted(r.approver());
            case REJECTED -> Approval.rejected(r.approver(),
                    r.comment() != null ? r.comment() : "approval rejected");
            case TIMED_OUT -> Approval.timedOut();
            case PENDING -> throw new IllegalStateException(
                    "审批记录仍是 PENDING 却已退出等待循环：" + r.id());
        };
    }

    private void sleep() {
        try {
            Thread.sleep(pollInterval.toMillis());
        } catch (InterruptedException e) {
            // 中断意味着这次调用不该再继续等。恢复标志位后按超时处理——
            // 绝不能把中断吞掉然后继续轮询，那会让线程无法被取消。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待审批时被中断", e);
        }
    }
}
