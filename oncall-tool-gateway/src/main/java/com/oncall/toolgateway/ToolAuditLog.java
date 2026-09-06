package com.oncall.toolgateway;

/**
 * 工具执行审计 —— <b>只追加的事件流</b>。
 *
 * <p><b>幂等职责已经从这里移走</b>，见 {@link ToolExecutionLedger}。
 * 原先这个接口同时管审计和幂等，后果是幂等状态只能存在内存里：
 * 多实例下两个实例各有一份 map，同一个重试请求打到不同实例就会被执行两次，
 * 而二次扩容、二次重启是会出真事故的。
 *
 * <p>两者的生命周期也不同：审计是只追加、保留 180 天、一次调用可以有多条事件；
 * 幂等账本是可变状态、一次调用一行、失败后要能删除以允许重试。
 * 把可变状态塞进只追加的日志表，会让「删掉一行以允许重试」变成篡改审计记录。
 *
 * <h2>为什么接口从 5 个 {@code recordXxx} 收敛成 1 个 {@code record}</h2>
 *
 * 原先是 {@code recordSuccess(idempotencyKey, toolName, args, result)} 这样的签名。
 * 它<b>喂不满 {@code tool_audit_log} 的必填列</b>：
 *
 * <table border="1">
 *   <caption>原签名的缺口</caption>
 *   <tr><th>NOT NULL 列</th><th>原签名能否给出</th></tr>
 *   <tr><td>{@code trace_id}</td><td>❌ 生产代码里 {@code traceId} 零命中，没有任何东西能产出它</td></tr>
 *   <tr><td>{@code tool_source}</td><td>❌ 分不清 LOCAL 与 MCP</td></tr>
 *   <tr><td>{@code risk_level}</td><td>❌ 没有风险级</td></tr>
 *   <tr><td>{@code gate_outcome}</td><td>⚠️ 只能靠方法名反推，而 {@code recordClamped} 与
 *       {@code recordApproval} 之间分不清 {@code PASSED} 与 {@code CLAMPED}</td></tr>
 *   <tr><td>{@code args_masked}</td><td>❌ 传的是未脱敏原文，而列名说它已脱敏</td></tr>
 * </table>
 *
 * 当时的选择只有「往必填列塞假值」或「不写这个实现」，选了后者——
 * <b>一张字段造假的审计表比没有审计更糟</b>：它让人以为查得到，
 * 而查出来的 trace 全是同一个占位串。
 *
 * <p>现在上下文作为显式参数传进来（{@link ToolAuditEvent} 把 7 个必填列全变成 record 组件），
 * 少一个字段就构造不出对象，实现方再也没有猜的余地。
 *
 * <p><b>一次调用可以产生多条事件</b>（夹紧后继续执行 = {@code CLAMPED} + {@code PASSED} 两行），
 * 所以这里是「事件流」而不是「每次调用一行」。
 */
public interface ToolAuditLog {

    /**
     * 追加一条审计事件。
     *
     * <p><b>实现不得吞掉异常</b>：审计写不进去必须让调用方知道。
     * 静默失败的审计等于没有审计，而且比没有更危险——它让人以为有。
     *
     * @param event 已完成校验与脱敏的事件
     */
    void record(ToolAuditEvent event);
}
