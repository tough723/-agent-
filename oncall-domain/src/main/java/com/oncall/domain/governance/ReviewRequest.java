package com.oncall.domain.governance;

import java.util.Objects;

/**
 * 一次待复核的判定输入。
 *
 * <p>刻意<b>不含任何存储访问</b>：判定是纯函数，读当前值、删待复核单、
 * 写审计都是调用方的事。这样"规则"能被单测穷举，
 * 而"规则"与"存储"混在一起时，测一条规则就要搭一套存储。
 *
 * @param subjectLabel    被改对象的展示名，只用于拼提示语（配置键 / 工具名）。
 *                        <b>不参与判定</b>——判定只认快照与当前值是否相等
 * @param requester       发起人
 * @param expiresAtMillis 过期时刻。双人复核的意义是「两个独立的人在
 *                        <b>同一时间点</b>都认为这个改动是对的」；
 *                        挂三天的单子，第二天复核的人面对的是完全不同的系统状态
 * @param snapshotAtProposal 发起时的生效值快照，{@code null} 表示当时无值
 * @param currentValue    此刻的生效值，{@code null} 表示此刻无值
 * @param reviewer        正在发起复核的人
 */
public record ReviewRequest(
        String subjectLabel,
        String requester,
        long expiresAtMillis,
        String snapshotAtProposal,
        String currentValue,
        Operator reviewer
) {

    public ReviewRequest {
        if (requester == null || requester.isBlank()) {
            throw new IllegalArgumentException("requester 不能为空——没有发起人的单子无法判定自审");
        }
        Objects.requireNonNull(reviewer, "reviewer");
        // subjectLabel 允许为 null：它只进提示语，缺了顶多消息难看，
        // 不该让一次安全判定因为展示字段而抛异常。
    }

    /** 发起之后、复核之前，被改对象是否已经变了。 */
    public boolean isStale() {
        if (snapshotAtProposal == null) {
            return currentValue != null;
        }
        return !snapshotAtProposal.equals(currentValue);
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }
}
