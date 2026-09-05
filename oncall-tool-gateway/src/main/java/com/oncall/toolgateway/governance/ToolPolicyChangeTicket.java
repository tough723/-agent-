package com.oncall.toolgateway.governance;

import com.oncall.domain.tool.ToolPolicy;

/**
 * 一张已发起、等待第二人复核的工具策略变更单。
 *
 * <p><b>为什么不复用 {@code PendingChange}</b>：配置侧的载荷是两个 {@code String}
 * （旧值 / 新值），工具侧是一个 {@link ToolPolicy} 记录加一个变更类型。
 * 硬塞进同一个记录会让两边都长出一堆"这个字段在我这儿没意义"。
 * <b>共享的是判定规则</b>（{@code TwoPersonReview}），<b>不是单据形状</b>——
 * 这才是正确的接缝：规则分叉才会出事故，单据形状分叉不会。
 *
 * @param id                   单号，服务端生成
 * @param change               拟议变更
 * @param requester            发起人
 * @param reason               发起理由，必填
 * @param signatureAtProposal  发起时该工具策略的签名快照，{@code null} 表示当时不在白名单里。
 *                             <b>存签名而不是存整个策略</b>：复核时要回答的问题是
 *                             "它变没变"，不是"它当时长什么样"；后者查审计更准
 * @param createdAtMillis      发起时间
 * @param expiresAtMillis      过期时间。理由与配置侧一致：双人复核的意义是
 *                             「两个独立的人在<b>同一时间点</b>都认为这个改动是对的」
 */
public record ToolPolicyChangeTicket(
        String id,
        ToolPolicyChange change,
        String requester,
        String reason,
        String signatureAtProposal,
        long createdAtMillis,
        long expiresAtMillis
) {

    public ToolPolicyChangeTicket {
        if (requester == null || requester.isBlank()) {
            throw new IllegalArgumentException("requester 不能为空");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("变更理由必填——没有理由的变更无法审计，"
                    + "也无法让复核人判断这个改动该不该批");
        }
    }

    public boolean isExpired(long nowMillis) {
        return nowMillis >= expiresAtMillis;
    }

    /**
     * 工具策略的签名。
     *
     * <p>用于 {@code STALE} 判定：发起之后、复核之前，这条策略有没有被别人改过。
     * 若改过，复核者判断的依据已经失效，必须重新发起。
     *
     * <p>{@code argsJsonSchema} 只取长度而不取内容：它可能是一段很长的 JSON，
     * 而这个签名会出现在给复核人看的提示语里。长度足以判断"变没变"，
     * 真要看内容应该去查审计。
     *
     * @param policy 策略；{@code null} 表示该工具不在白名单里，返回 {@code null}
     */
    public static String signatureOf(ToolPolicy policy) {
        if (policy == null) {
            return null;
        }
        return policy.source() + "/" + policy.risk()
                + ";approval=" + policy.requiresApproval()
                + ";dual=" + policy.requiresDualApproval()
                + ";timeout=" + (policy.approvalTimeout() == null
                        ? "null" : policy.approvalTimeout().toSeconds() + "s")
                + ";schemaLen=" + (policy.argsJsonSchema() == null
                        ? "null" : String.valueOf(policy.argsJsonSchema().length()));
    }

    /** 发起之后、复核之前，这条策略是否已被别人改过。 */
    public boolean staleAgainst(String currentSignature) {
        if (signatureAtProposal == null) {
            return currentSignature != null;
        }
        return !signatureAtProposal.equals(currentSignature);
    }
}
