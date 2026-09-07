package com.oncall.app;

import com.oncall.toolgateway.ApprovalRecordStore;
import com.oncall.toolgateway.ArgClamper;
import com.oncall.toolgateway.GuardedToolCallback;
import com.oncall.toolgateway.IdempotencyStore;
import com.oncall.toolgateway.KillSwitch;
import com.oncall.toolgateway.PollingApprovalGate;
import com.oncall.toolgateway.ToolAuditContext;
import com.oncall.toolgateway.ToolAuditLog;
import com.oncall.toolgateway.ToolExecutionLedger;
import com.oncall.toolgateway.ToolPolicyEngine;
import com.oncall.toolgateway.clamp.JsonScaleArgsAdapter;
import com.oncall.toolgateway.clamp.ReplicaStatePort;
import com.oncall.toolgateway.clamp.ScaleReplicasClamper;

import org.springframework.ai.tool.ToolCallback;

import java.util.Objects;

/**
 * 工具网关的装配点：把「防提示注入的参数夹紧」这条链真正 new 出来。
 *
 * <p><b>这个类存在的理由是一次测量。</b>轨道 C5 建好了 {@link JsonScaleArgsAdapter}，
 * 轨道 D1 量出它的<b>生产引用数是 0</b>——
 * {@code GuardedToolCallback} 与 {@code McpToolRegistrar} 拿到的全是
 * {@code ArgClamper.NOOP}。缺的从来不是实现，是<b>有一个地方负责 new 它</b>。
 *
 * <p><b>它挡掉的具体陷阱：</b>
 * {@link ScaleReplicasClamper} 的 javadoc 自称「防提示注入后果的确定性防线」，
 * 但它接受的是 {@code ScaleRequest}，<b>不是</b>模型生成的 JSON 字符串；
 * 而网关传给 {@link ArgClamper} 的正是 JSON 字符串。
 * 于是装配的人很容易以为「把 {@code ScaleReplicasClamper} 传进去就行」——
 * 好在 {@code ScaleReplicasClamper} <b>没有</b>实现 {@code ArgClamper}，
 * 那样写编译不过。真正的形状是<b>两层</b>：
 *
 * <pre>{@code
 * 模型生成的 JSON ──> JsonScaleArgsAdapter ──> ScaleReplicasClamper ──> 夹紧后的 JSON
 *                     （解析 + 改树）          （纯算术 + 拒绝）
 * }</pre>
 *
 * <p>本类把这个两层结构写死在一个方法里，装配的人不需要自己记住它。
 *
 * <p><b>更正（轨道 D2-b）：</b>本类的初版曾写「不构造 {@code GuardedToolCallback}，
 * 因为 {@code ApprovalGate} 生产实现数为 0」。<b>那句话是错的。</b>
 * {@code PollingApprovalGate} 一直是 {@code ApprovalGate} 的生产实现
 * （轮询 {@code approval_record}，超时写 {@code TIMED_OUT}）。
 * 错因是我用 {@code grep ... | head -6} 的<b>截断样本</b>断言了一个全称否定命题，
 * 而 {@code grep -r} 的文件遍历顺序并不稳定。
 * 所以现在 {@link #guardedToolCallback} 是把完整回调装配出来的。
 *
 * <p>注意 {@code WecomApprovalGate} 仍是 M1 剩余项，但它是<b>通知渠道</b>那一层
 * （企微卡片 + 超时升级），不是闸门本体——事实来源是数据库，企微只是催人的手段。
 *
 * @see JsonScaleArgsAdapter
 * @see ScaleReplicasClamper
 */
public final class ToolGatewayAssembly {

    private ToolGatewayAssembly() {
        // 工具类：不给实例。装配是函数式的——同样的输入必须给出同样的链。
    }

    /**
     * 装配 {@code scale_replicas} 工具的参数夹紧链。
     *
     * @param replicaState 副本状态查询端口。查询失败时它必须抛异常——
     *                     {@link ScaleReplicasClamper} 的契约是「查不到就拒绝」，
     *                     <b>绝不允许「查不到就放行」</b>，
     *                     因为放行等于给提示注入留了一个绕过整道防线的入口。
     * @param limits       按服务给出的夹紧上限。不同服务的重要性不同，
     *                     {@code maxDelta} / {@code minReplicas} 必须可分别配置，
     *                     所以这里是端口而不是常量。
     * @return 可以直接交给 {@code GuardedToolCallback} / {@code McpToolRegistrar} 的夹紧器
     * @throws NullPointerException 任一参数为 null
     */
    public static ArgClamper scaleReplicasClamper(
            ReplicaStatePort replicaState,
            ScaleReplicasClamper.PolicyProvider limits) {
        Objects.requireNonNull(replicaState, "replicaState");
        Objects.requireNonNull(limits, "limits");
        return new JsonScaleArgsAdapter(replicaState, limits);
    }

    /**
     * 装配一个完整的、七道关卡齐备的 {@link GuardedToolCallback}。
     *
     * <p><b>这个方法是本类真正的价值所在。</b>它接收的是<b>原料</b>
     * （存储、端口），在内部构造<b>策略</b>（审批闸门、夹紧器）——
     * 于是调用方<b>没有机会</b>忘掉其中任何一个。
     *
     * <p>对照轨道 D1 之前的状态：那时 {@code argClamper} 是调用方传进来的，
     * 传 {@code null} 就静默变成不夹紧；{@code approvalGate} 同理。
     * 两道防线都依赖「装配的人记得传」，而这正是会被忘掉的那类事。
     *
     * @param approvalRecords 审批记录存储。生产应当是
     *                        {@code JdbcApprovalRecordStore}——
     *                        {@code PollingApprovalGate} 靠轮询这张表拿结论，
     *                        换成内存实现会让多实例之间互相看不见对方的审批。
     * @throws NullPointerException 任一参数为 null
     */
    public static GuardedToolCallback guardedToolCallback(
            ToolCallback delegate,
            ToolPolicyEngine policyEngine,
            KillSwitch killSwitch,
            ToolAuditLog auditLog,
            ToolAuditContext auditContext,
            IdempotencyStore idempotencyStore,
            ToolExecutionLedger ledger,
            ApprovalRecordStore approvalRecords,
            ReplicaStatePort replicaState,
            ScaleReplicasClamper.PolicyProvider limits,
            String runId,
            int step) {
        Objects.requireNonNull(delegate, "delegate");
        Objects.requireNonNull(policyEngine, "policyEngine");
        Objects.requireNonNull(killSwitch, "killSwitch");
        Objects.requireNonNull(auditLog, "auditLog");
        Objects.requireNonNull(auditContext, "auditContext");
        Objects.requireNonNull(idempotencyStore, "idempotencyStore");
        Objects.requireNonNull(ledger, "ledger");
        Objects.requireNonNull(approvalRecords, "approvalRecords");
        Objects.requireNonNull(runId, "runId");

        return new GuardedToolCallback(delegate, policyEngine, killSwitch,
                // 闸门用默认轮询间隔（2s）与系统时钟；要测超时逻辑就直接
                // new PollingApprovalGate(store, interval, clock, requester)，
                // 那个四参构造器就是为可测性留的。
                new PollingApprovalGate(approvalRecords),
                auditLog, auditContext, idempotencyStore, ledger,
                scaleReplicasClamper(replicaState, limits),
                runId, step);
    }
}
