package com.oncall.toolgateway.governance;

import com.oncall.domain.tool.ToolPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次工具策略变更的<b>风险方向</b>判定。
 *
 * <h2>核心规则：治理要求跟着风险方向走，而不是跟着"有没有改动"走</h2>
 *
 * 不是所有策略变更都同样危险：
 * <ul>
 *   <li><b>放宽</b>（让 AI 能做更多）—— 必须双人复核；</li>
 *   <li><b>收紧</b>（让 AI 能做更少）—— 不需要。</li>
 * </ul>
 *
 * 这条不对称是刻意的。默认拒绝（default-deny）意味着<b>撤掉一条策略等于让系统更安全</b>；
 * 如果撤掉一个危险工具也要凑齐两个人，结果就是那个危险工具继续留在白名单里——
 * 把安全改进卡在双人流程后面，恰好保护了它本该消除的风险。
 *
 * <h2>哪些维度算"放宽"</h2>
 *
 * 只要<b>任意一个</b>维度放宽，整体就算放宽。刻意用"任一"而不是打分：
 * 打分意味着可以拿一处收紧去抵一处放宽，
 * 而"关掉审批"和"把风险从 HIGH 降到 READ_ONLY"不是可以互相抵消的东西。
 *
 * <ol>
 *   <li>{@code risk} 下降（HIGH → LOW → READ_ONLY）；</li>
 *   <li>{@code requiresApproval} 由 true 变 false；</li>
 *   <li>{@code requiresDualApproval} 由 true 变 false；</li>
 *   <li>{@code argsJsonSchema} 由有变无（去掉了参数级校验）；</li>
 *   <li>{@code approvalTimeout} 变长（超时是"到点自动拒绝并升级"，
 *       等得越久越可能等到人批下来）。</li>
 * </ol>
 *
 * <p>{@code source} 变化（LOCAL ↔ MCP）<b>不算</b>宽窄——它换的是信任边界，
 * 不是权限大小。但它会被记进 {@code reasons} 里，因为复核的人需要知道。
 */
public record PolicyRiskDelta(boolean widens, List<String> reasons) {

    public PolicyRiskDelta {
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }

    /**
     * 比较当前策略与拟议策略。
     *
     * @param current  当前生效策略；{@code null} 表示该工具当前<b>不在</b>白名单里
     * @param proposed 拟议策略；{@code null} 表示拟<b>撤销</b>该工具
     */
    public static PolicyRiskDelta between(ToolPolicy current, ToolPolicy proposed) {
        // 撤销：默认拒绝之下，移除一条策略让系统严格变安全。
        if (proposed == null) {
            if (current == null) {
                return new PolicyRiskDelta(false, List.of("撤销一条并不存在的策略，无实际效果"));
            }
            return new PolicyRiskDelta(false,
                    List.of("撤销工具 " + current.toolName() + "（默认拒绝生效，方向为收紧）"));
        }
        // 新增：从"不允许"到"允许"，无条件算放宽。
        //
        // 刻意不给 READ_ONLY 开例外：把一个工具加进白名单，就等于允许模型调用它，
        // 而"只读"这个标签本身正是发起人的断言——复核人要做的事就是核对这个断言。
        // 数据泄露不需要写权限。
        if (current == null) {
            return new PolicyRiskDelta(true,
                    List.of("新增工具 " + proposed.toolName()
                            + " 到白名单（此前默认拒绝）：source=" + proposed.source()
                            + "，risk=" + proposed.risk()));
        }
        if (!current.toolName().equals(proposed.toolName())) {
            throw new IllegalArgumentException("不能在一次变更里改工具名：current="
                    + current.toolName() + "，proposed=" + proposed.toolName()
                    + "。改名等于撤销 + 新增，必须拆成两次变更，否则审计里看不出原来的工具被撤了");
        }
        return diff(current, proposed);
    }

    private static PolicyRiskDelta diff(ToolPolicy c, ToolPolicy p) {
        List<String> r = new ArrayList<>();

        if (p.risk().ordinal() < c.risk().ordinal()) {
            r.add("风险等级下调 " + c.risk() + " → " + p.risk());
        }
        if (c.requiresApproval() && !p.requiresApproval()) {
            r.add("取消了人工审批");
        }
        if (c.requiresDualApproval() && !p.requiresDualApproval()) {
            r.add("取消了双人复核");
        }
        if (c.argsJsonSchema() != null && p.argsJsonSchema() == null) {
            r.add("去掉了参数级 JSON Schema 校验");
        }
        Duration ct = c.approvalTimeout();
        Duration pt = p.approvalTimeout();
        if (ct != null && pt != null && pt.compareTo(ct) > 0) {
            r.add("审批超时由 " + ct.toSeconds() + "s 延长到 " + pt.toSeconds() + "s");
        }
        if (c.source() != p.source()) {
            // 不算放宽，但复核人必须知道：换的是信任边界。
            r.add("来源由 " + c.source() + " 改为 " + p.source() + "（信任边界变化，不计入宽窄）");
        }

        // "放宽"只由前五项决定；source 那一条只是提示，不参与判定。
        boolean widens = r.stream().anyMatch(s -> !s.startsWith("来源由"));
        if (!widens) {
            r.add("其余维度均为收紧或不变");
        }
        return new PolicyRiskDelta(widens, Collections.unmodifiableList(r));
    }
}
