package com.oncall.domain.governance;

/**
 * 双人复核的<b>唯一</b>决策核心。
 *
 * <h2>为什么必须只有一份</h2>
 *
 * 项目里有两类受治理的变更：<b>配置项</b>（{@code OnCallConfigRegistry}）
 * 与<b>工具白名单</b>（{@code ToolPolicyEngine}）。后者的后果更重——
 * 加一条 MCP 工具策略等于放行一个远端工具，而白名单是整个安全模型的事实来源。
 *
 * 如果两处各写一遍复核逻辑，它们一定会分叉，而且分叉不会报错：
 * 「配置侧不允许自审、工具侧忘了这一条」在功能测试里完全看不出来，
 * 只有在有人真的自审自批时才会暴露——那时候事故已经发生了。
 * 所以规则放在领域层，两边都只是它的调用方。
 *
 * <h2>四条规则，顺序不可调换</h2>
 *
 * <ol>
 *   <li><b>过期</b> → 单子还活着才谈得上复核；</li>
 *   <li><b>角色不够</b> → 复核者必须是 {@link Operator.Role#ADMIN}。
 *       <b>必须排在「已被改过」之前</b>：后者的提示里含<b>当前生效值</b>，
 *       返回给无权复核的人就是信息泄露；</li>
 *   <li><b>不能复核自己</b> → 「双人」的意义在于两个独立判断。
 *       排在「已被改过」之前，是因为自审者不该从错误消息里读到当前值；</li>
 *   <li><b>发起后已被改过</b> → 复核者判断的依据已失效。</li>
 * </ol>
 *
 * 这个顺序由 {@code TwoPersonReviewTest} 直接断言（构造一个同时满足
 * 「无权 + 已过期 + 已被改过」的输入，看返回哪一个），
 * 而不是靠读代码时的自觉。
 *
 * <h2>刻意不做的事</h2>
 *
 * <ul>
 *   <li><b>不访问存储</b>。判定是纯函数；删待复核单、写审计是调用方的事。
 *       这样四条规则能被穷举单测，而不用为每条规则搭一套存储。</li>
 *   <li><b>不把「需要 ADMIN」做成参数</b>。写成 {@code Predicate<Operator>}
 *       会让调用方有机会传一个恒真的判断——守卫自己的东西不能交给被守卫的对象，
 *       与 {@code ConfigAccessPolicy.HIGH_RISK_KEYS} 刻意硬编码是同一个理由。</li>
 *   <li><b>不提供「跳过复核」的入口</b>。没有 {@code force} 参数，
 *       也没有超级管理员角色（见 {@link Operator.Role}）。</li>
 * </ul>
 */
public final class TwoPersonReview {

    private TwoPersonReview() {
    }

    /**
     * 判定一次复核请求。
     *
     * @param request   判定输入，不含任何存储访问
     * @param nowMillis 当前时刻。由调用方传入而不是内部取
     *                  {@code System.currentTimeMillis()}，否则过期逻辑无法确定性测试
     */
    public static ReviewOutcome evaluate(ReviewRequest request, long nowMillis) {
        if (request.isExpired(nowMillis)) {
            return ReviewOutcome.deny(ReviewVerdict.EXPIRED,
                    "待复核单已过期，请重新发起——隔了这么久，系统状态可能已经变了，当时的判断不再成立");
        }
        if (request.reviewer().role() != Operator.Role.ADMIN) {
            return ReviewOutcome.deny(ReviewVerdict.NOT_AUTHORIZED,
                    "复核受治理的变更需要 ADMIN 权限");
        }
        if (request.requester().equals(request.reviewer().principal())) {
            return ReviewOutcome.deny(ReviewVerdict.SELF_APPROVAL,
                    "不能复核自己发起的变更——双人复核的意义在于两个独立的判断");
        }
        if (request.isStale()) {
            return ReviewOutcome.deny(ReviewVerdict.STALE,
                    label(request) + "在发起复核后已被其他人修改（发起时 "
                            + show(request.snapshotAtProposal())
                            + "，现在 " + show(request.currentValue())
                            + "），本次复核已失效，请重新发起");
        }
        return ReviewOutcome.allow();
    }

    private static String label(ReviewRequest r) {
        String l = r.subjectLabel();
        return (l == null || l.isBlank()) ? "该对象" : l;
    }

    /** null 在提示语里显示为「无」，而不是字面量 "null"。 */
    private static String show(String v) {
        return (v == null) ? "无" : v;
    }
}
