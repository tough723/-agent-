package com.oncall.domain.trace;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 链路 ID —— {@code agent_run} / {@code tool_audit_log} / {@code approval_record}
 * 三张表的 {@code trace_id} 列的唯一合法来源。
 *
 * <h2>为什么需要这个类</h2>
 *
 * <p>{@code trace_id} 在三张表里都是 {@code NOT NULL}，
 * 而本类出现之前 <b>生产代码里没有任何东西能产出一个 traceId</b>：
 * 在各模块的 {@code src/main} 里搜 {@code new ToolAuditContext(} 与
 * {@code ToolAuditContext.of(}，4 条命中全是消费侧
 * （{@code of()} 工厂自身 + 两处 JDBC 读回）。
 *
 * <p>后果不是「少一个字段」：{@code ToolAuditContext} 的构造器对 traceId 做
 * {@code requireNonBlank}，而 {@code GuardedToolCallback} 与 {@code McpToolRegistrar}
 * 都要求一个非空的 {@code ToolAuditContext}。
 * <b>也就是说生产代码根本构造不出一个 {@code GuardedToolCallback}。</b>
 *
 * <h2>两条校验都是安全控制，不是整洁问题</h2>
 *
 * <ol>
 *   <li><b>字符集 —— 防日志注入。</b>{@code ToolAuditContext.toString()} 会把 traceId
 *       直接拼进日志行。如果 traceId 允许换行，那么<b>任何能控制告警 webhook 请求头的人
 *       都能伪造日志行</b>——包括伪造一条「操作已审批」。
 *       所以只允许 {@code [A-Za-z0-9._-]}。</li>
 *   <li><b>长度 ≤64 —— 对齐列宽。</b>{@code trace_id VARCHAR(64)}。
 *       超长不该被静默截断：截断会让两条不同的链路撞上同一个 trace，
 *       而那表现为「一次排查里混进了另一次的操作」，几乎不可能查出来。</li>
 * </ol>
 *
 * <h2>本类刻意不含时间戳，也不读时钟</h2>
 *
 * <p>本项目的规则是「谁拥有流程谁负责传时刻」（见 {@code RunProvenance}）。
 * 一个 trace id 的正确性不需要时间，所以这里干脆不碰时钟——
 * 少一次时钟读取就少一次「同一个流程读了两次时钟」的机会。
 * 需要按时间排序时用 {@code agent_run.created_at}，那才是有归属的时刻。
 */
public final class TraceId {

    /** 与 DDL 列宽一致：{@code trace_id VARCHAR(64)}。 */
    public static final int MAX_LENGTH = 64;

    /** 前缀让 trace 在一大片 UUID 里能被一眼认出来，也便于日志里 grep。 */
    public static final String PREFIX = "oc-";

    /**
     * 合法字符集。<b>不含空白、不含引号、不含换行</b>——见类注释的日志注入说明。
     *
     * <p>单个字符类的重复，没有交替也没有嵌套量词，
     * 所以不存在 {@code java.util.regex} 的递归回溯问题；
     * 而且 {@link #rejectionReason} <b>先查长度再跑正则</b>，
     * 正则永远只会看到 ≤64 个字符的输入。
     */
    private static final Pattern SAFE_CHARSET = Pattern.compile("[A-Za-z0-9._-]+");

    private final String value;

    private TraceId(String value) {
        this.value = value;
    }

    /**
     * 铸一个新的。
     *
     * <p><b>一次 {@code agent_run} 只该铸一次</b>，之后全程往下传
     * （见 {@code ToolAuditContext.forStep}，它在结构上保证 traceId 不变）。
     * 每步各铸一个的话，{@code trace_id} 就失去了「把一次排查的所有操作串起来」
     * 这唯一的用途。
     */
    public static TraceId mint() {
        return new TraceId(PREFIX + UUID.randomUUID());
    }

    /**
     * 采纳上游传来的 trace（告警 webhook、W3C {@code traceparent}、上游服务）。
     *
     * <p><b>能采纳就一定要采纳</b>：自己另铸一个的话，
     * 本系统的链路就和上游的链路断开了，跨系统排查时对不上。
     *
     * @throws IllegalArgumentException 上游给的值不可用；
     *                                  用 {@link #rejectionReason} 先取原因再决定降级
     */
    public static TraceId adopt(String inbound) {
        String reason = rejectionReason(inbound).orElse(null);
        if (reason != null) {
            throw new IllegalArgumentException("上游 trace 不可用：" + reason);
        }
        return new TraceId(inbound.trim());
    }

    /**
     * 上游 trace 为什么不可用；可用时返回 {@code Optional.empty()}。
     *
     * <p><b>为什么要单独给这个方法，而不是让 {@link #adopt} 抛异常就完事</b>：
     * 告警接入路径上，一个畸形的 trace 头<b>绝不能让整个告警被丢掉</b>——
     * 正确做法是铸一个新的、并把「上游 trace 不可用」这件事记下来。
     * 而静默降级正是本项目反复踩的那类坑，
     * 所以降级的<b>原因必须能拿到</b>，由调用方决定怎么记。
     */
    public static Optional<String> rejectionReason(String inbound) {
        if (inbound == null) {
            return Optional.of("为 null");
        }
        String t = inbound.trim();
        if (t.isEmpty()) {
            return Optional.of("为空白");
        }
        // 长度必须在正则之前查：这既避免了超长输入进正则，
        // 也让「又长又含非法字符」的输入报出更有用的那条原因。
        if (t.length() > MAX_LENGTH) {
            return Optional.of("长度 " + t.length() + " 超过列宽 " + MAX_LENGTH);
        }
        if (!SAFE_CHARSET.matcher(t).matches()) {
            return Optional.of("含非法字符（只允许 A-Za-z0-9._-）");
        }
        return Optional.empty();
    }

    public String value() {
        return value;
    }

    /** 直接就是那个字符串，可以原样进 SQL 参数与日志。 */
    @Override
    public String toString() {
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof TraceId other && value.equals(other.value);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(value);
    }
}
