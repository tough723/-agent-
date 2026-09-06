package com.oncall.toolgateway;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 审批记录存储。
 *
 * <p><b>为什么必须有这个接口，而不是让审批闸门自己写 SQL</b>：
 * 审批闸门（企微/钉钉卡片、轮询、超时升级）是<b>易变</b>的一层，
 * 而「谁在什么时候批了什么」是<b>不能变</b>的一层。
 * 把两者绑在一起，换一次通知渠道就要重测一遍持久化。
 *
 * <p><b>生产必须用 {@link JdbcApprovalRecordStore}</b>。
 * 这张表的保留期是永久的、是责任归属的唯一凭据，
 * 内存实现进程一重启就没了——那不是「审计不全」，是<b>责任无法追溯</b>。
 */
public interface ApprovalRecordStore {

    /**
     * 写入一条待审批记录。
     *
     * <p>实现必须在<b>通知审批人之前</b>完成写入：
     * 先通知后写库的话，一旦写库失败就出现
     * 「有人批了但系统里查不到这次审批」的状态，
     * 而那正是责任归属最容易扯不清的地方。
     *
     * @throws IllegalArgumentException id 已存在（同一次审批不能提交两次）
     */
    void insert(ApprovalRecord record);

    /**
     * 把一条 PENDING 记录改成终态。
     *
     * <p><b>必须是条件更新</b>（{@code WHERE decision='PENDING'}）：
     * 两个审批人同时点批准，只能有一个生效，
     * 否则同一行会被写两次，而「谁批的」变成后写的那个人。
     *
     * @return 是否真的由本次调用完成了这次决定；{@code false} 表示已被别人决定或记录不存在
     */
    boolean decide(String id, ApprovalDecision outcome, String approver, String comment);

    Optional<ApprovalRecord> find(String id);

    /** 当前所有待审批的记录。「现在有哪些操作卡着等人批」靠这个查。 */
    List<ApprovalRecord> pending();

    /**
     * 已提交但超过 {@code requestedBefore} 仍未决定的记录。
     *
     * <p>超时升级的输入。<b>刻意用「请求时间早于某刻」而不是「已过期的」</b>：
     * 过期与否取决于每个工具策略各自的 {@code approvalTimeout}，
     * 而那个值可能在等待期间被改掉；按请求时间筛出来的集合是稳定的，
     * 判定留给调用方。
     */
    List<ApprovalRecord> pendingRequestedBefore(Instant requestedBefore);

    /** 行数，供自检与指标使用。 */
    int count();
}
