package com.oncall.agent.execute;

import com.oncall.domain.run.AgentRun;
import com.oncall.domain.run.RunStatus;

import java.util.Objects;

/**
 * 一次执行的结果。
 *
 * <p><b>为什么 {@code stepsExecuted} 与 {@code stoppedReason} 都要带出来</b>：
 * 一次「没有跑完」的执行有很多种，而它们在运维上是完全不同的事——
 * 预算耗尽（该调预算）、放权不足（该走人工审批）、工具报错（该修工具）。
 * 只返回一个 {@code AgentRun} 的话，调用方得自己去猜是哪一种。
 */
public record ExecutionResult(AgentRun run, int stepsExecuted, String stoppedReason) {

    public ExecutionResult {
        Objects.requireNonNull(run, "run");
        if (stepsExecuted < 0) {
            throw new IllegalArgumentException("stepsExecuted 不得为负：" + stepsExecuted);
        }
        // 没跑完就必须说清为什么；跑完了就不该有停止原因。
        if (run.status() == RunStatus.SUCCEEDED && stoppedReason != null) {
            throw new IllegalArgumentException(
                    "状态是 SUCCEEDED 却带了 stoppedReason=" + stoppedReason
                            + "——两者矛盾，调用方会读到自相矛盾的结果");
        }
        if (run.status() != RunStatus.SUCCEEDED && stoppedReason == null) {
            throw new IllegalArgumentException("状态是 " + run.status()
                    + " 却没有 stoppedReason——「没跑完」必须说清为什么，否则调用方只能猜");
        }
    }

    public boolean completed() {
        return run.status() == RunStatus.SUCCEEDED;
    }

    /** 是否已交回人工。这是放权不足时的正常出口，不是故障。 */
    public boolean handedOver() {
        return run.status() == RunStatus.HANDED_OVER;
    }
}
