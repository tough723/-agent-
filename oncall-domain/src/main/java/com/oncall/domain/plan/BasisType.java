package com.oncall.domain.plan;

/**
 * 一个计划步骤的「依据来源」类型。
 *
 * <p>为什么要有这个枚举：{@code 修复方案.md} F2.3 规定
 * <b>高危步骤必须能追溯到可信来源</b>，而「可信」的定义是
 * 「告警规则本身 / 匹配到的 Runbook；<b>日志文本不算</b>」。
 *
 * <p>日志文本不算，是因为日志是<b>攻击面</b>：F2.4 的注入测试集里就有
 * 「在日志里写一段假装是管理员批准的指令」这类样本。
 * 如果日志文本能当高危操作的依据，那注入就等于拿到了授权。
 *
 * <p><b>把「可信」写成本枚举的方法而不是校验器里的 if</b>：
 * 将来加一种新来源（比如 CMDB 查询结果）时，
 * 作者必须显式回答「它可信吗」，而不是默认落进某个分支。
 */
public enum BasisType {

    /** 告警规则本身。可信。 */
    ALERT_RULE(true),

    /** 匹配到的 Runbook。可信。 */
    RUNBOOK(true),

    /**
     * 日志文本。<b>不可信</b>——它是注入攻击的载体。
     *
     * <p>刻意保留这个值而不是不收：Planner 确实会从日志里读出信息，
     * 把它记下来是对的；错的是拿它当高危操作的授权依据。
     * 删掉这个值会让 Planner 无处声明，反而丢失可观测性。
     */
    LOG_TEXT(false),

    /** 上一步工具的输出。不可信——工具输出同样可能含被注入的内容。 */
    TOOL_OUTPUT(false);

    private final boolean trusted;

    BasisType(boolean trusted) {
        this.trusted = trusted;
    }

    /** 是否可作为高危操作的授权依据。 */
    public boolean isTrusted() {
        return trusted;
    }
}
