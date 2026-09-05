package com.oncall.toolgateway.governance;

import java.util.List;

/**
 * 工具策略变更审计 —— 回答「<b>谁在什么时候放行/收紧了什么</b>」。
 *
 * <p>这正是当前缺的那一半：白名单是整个安全模型的事实来源，
 * 但到目前为止加一条策略既没有第二人复核，也没有可查的记录。
 *
 * <p>刻意<b>只追加</b>：审计的价值在于它不能被改写。
 * 一次变更可以产生多条事件（发起 / 复核通过 / 驳回 / 过期作废 / 直接生效），
 * 这与幂等账本"一次调用一行且失败要能删"的形状完全不同——
 * 后者见 {@code ToolExecutionLedger} 的类注释。
 */
public interface ToolPolicyChangeAudit {

    /**
     * 记录一次已生效的变更（含直接生效与复核通过两种）。
     *
     * @param atMillis 事件时刻，<b>由调用方传入</b>。审计实现不该自己取时钟：
     *                 {@link ToolPolicyGovernance} 持有一个可注入的时钟用于
     *                 确定性地测试过期，若审计另取 {@code System.currentTimeMillis()}，
     *                 同一个流程里就有两个时钟，测试里推进了一个另一个不动。
     */
    void recordApplied(ToolPolicyChangeTicket ticket, String actor, Outcome outcome, long atMillis);

    /** 记录一次被拒绝的复核，含拒绝原因——被拒的尝试同样是审计对象。 */
    void recordRejected(ToolPolicyChangeTicket ticket, String actor, String reason, long atMillis);

    /**
     * 记录一次发起（尚未生效）。让"发起了但没批下来"也可查。
     * 时刻直接取 {@code ticket.createdAtMillis()}——那就是发起时刻。
     */
    void recordProposed(ToolPolicyChangeTicket ticket);

    /** 某个工具的全部变更历史，按时间正序。 */
    List<Entry> history(String toolName);

    /** 最近的变更，按时间倒序。 */
    List<Entry> recent(int limit);

    /**
     * 审计条目。
     *
     * @param seq 插入序号，<b>用于给同一毫秒内的事件定序</b>。
     *            这不是实现细节而是审计的必备属性：
     *            「先发起还是先被拒」必须有唯一答案，而毫秒精度不够——
     *            发起后立刻驳回是正常操作，不是边缘情况。
     *            CI run 33986494127 就因为缺这一项而红：
     *            {@code recent(1)} 在同一毫秒的两条记录之间返回了 PROPOSED。
     */
    record Entry(String toolName, String actor, Outcome outcome, String reason,
                 String changeDescription, long atMillis, long seq) {

        /** 全序比较器：先按时刻，同一毫秒按插入序号。 */
        public static java.util.Comparator<Entry> order() {
            return java.util.Comparator.comparingLong(Entry::atMillis)
                    .thenComparingLong(Entry::seq);
        }
    }

    /** 变更的处置结果。 */
    enum Outcome {
        /** 已发起，等待复核。 */
        PROPOSED,
        /** 双人复核通过后生效。 */
        APPLIED_AFTER_REVIEW,
        /** 方向为收紧，无需复核，直接生效。 */
        APPLIED_DIRECTLY,
        /** 复核被拒。 */
        REJECTED
    }
}
