package com.oncall.agent.plan;

/**
 * 本次执行<b>根本不该重规划</b>。
 *
 * <h2>★ 为什么它和 {@link PlanProductionException} / {@link PlanRejectedException} 分开</h2>
 * <p>三个异常对应三种完全不同的处置，合并任意两个都会让两个不同的仪表盘
 * 看起来一样：
 *
 * <table border="1">
 *   <caption>三种异常的区分</caption>
 *   <tr><th>异常</th><th>含义</th><th>该看哪个指标</th></tr>
 *   <tr><td>{@code ReplanNotApplicableException}</td>
 *       <td><b>策略上不该重规划</b>（已成功／已交回人工／预算耗尽）</td>
 *       <td>重规划预算耗尽率 —— 这是<b>容量</b>问题</td></tr>
 *   <tr><td>{@link PlanProductionException}</td>
 *       <td>模型不可用或输出不可解析</td>
 *       <td>模型可用性 —— 这是<b>依赖</b>问题</td></tr>
 *   <tr><td>{@link PlanRejectedException}</td>
 *       <td>计划产出了但不安全</td>
 *       <td>危险计划率 —— 这是<b>模型行为</b>问题</td></tr>
 * </table>
 *
 * <p>尤其是「预算耗尽」：它是<b>预期内的正常出口</b>，
 * 和「模型挂了」混在一个计数里，就会把容量规划问题误读成稳定性事故。
 */
public class ReplanNotApplicableException extends RuntimeException {

    public ReplanNotApplicableException(String message) {
        super(message);
    }
}
