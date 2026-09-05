package com.oncall.toolgateway;

/**
 * 幂等键生成（Strategy 模式）。
 *
 * <p>键必须包含 {@code runId + 步序号 + 工具名 + 规范化后的参数摘要}：
 * <ul>
 *   <li>含 runId/步序号 → 同一 run 内重复投递只执行一次</li>
 *   <li>参数必须<b>先规范化</b>（key 排序、去空白），否则同语义不同字面量会产生不同键，幂等失效</li>
 * </ul>
 */
@FunctionalInterface
public interface IdempotencyStore {

    String keyFor(String runId, int step, String toolName, String canonicalArgs);
}
