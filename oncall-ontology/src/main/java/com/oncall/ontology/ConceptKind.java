package com.oncall.ontology;

/**
 * 概念的种类。
 *
 * <p>刻意只有五类，且<b>每类对应一组能力问题（Competency Question）</b>。
 * 加一类之前必须先加 CQ——这是防止本体范围失控的唯一有效手段。
 *
 * <p>范围界定与 CQ1~CQ8 见仓库根目录的《轻量本体设计.md》。
 */
public enum ConceptKind {

    /** 告警及其类别。CQ2：这个告警属于哪一类，同类以前怎么处理？ */
    ALERT,

    /** 服务。CQ1 / CQ3 / CQ6。 */
    SERVICE,

    /** 操作。CQ4：这个操作需要几个人审批？ */
    OPERATION,

    /** 处置手段（Runbook）。CQ5：这条 Runbook 还有效吗？ */
    REMEDIATION,

    /** 业务域。CQ1：「支付相关的所有服务」需要它——别名表只能一对一，域能一对多。 */
    DOMAIN
}
