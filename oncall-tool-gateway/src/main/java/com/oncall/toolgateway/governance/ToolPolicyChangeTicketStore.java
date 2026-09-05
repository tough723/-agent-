package com.oncall.toolgateway.governance;

import java.util.List;
import java.util.Optional;

/**
 * 工具策略变更单的存放端口。
 *
 * <p>定义为端口而不是直接用 Map，理由与配置侧的 {@code PendingChangeStore} 完全一致：
 * 多实例部署时，A 实例发起的单子必须能被 B 实例复核，
 * 否则"找另一个人点确认"会变成"找另一个人去点同一台机器"。
 */
public interface ToolPolicyChangeTicketStore {

    void put(ToolPolicyChangeTicket ticket);

    Optional<ToolPolicyChangeTicket> find(String id);

    /** 复核完成（通过或驳回）后移除，避免同一张单被复核两次。 */
    void remove(String id);

    /** 当前所有未决单子，按发起时间排序——顺序会进界面，必须稳定。 */
    List<ToolPolicyChangeTicket> open();
}
