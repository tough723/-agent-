package com.oncall.ontology;

/**
 * 服务的关键程度。
 *
 * <p><b>为什么是属性而不是子类</b>：按 OntoClean 的刚性判据，
 * 「核心」是**非刚性**的——一个服务被提升为核心时，它仍然是同一个服务。
 * 非刚性属性不能做子类划分的依据，只能是角色（role）。
 *
 * <p>如果建成 {@code Service} 的子类 {@code CoreService}，会有三个具体坏处：
 * <ul>
 *   <li>服务升级要**改类型、迁实例**，而不是改一个字段；</li>
 *   <li>类互斥导致「核心」无法表达程度（现实里是分级的）；</li>
 *   <li>推理机会把它当本质属性，推出错误结论。</li>
 * </ul>
 */
public enum Criticality {

    /** 核心服务：高危操作需两人审批，资源类告警的放权上限为 S1。 */
    CRITICAL,

    /** 重要服务。 */
    HIGH,

    /** 普通服务。 */
    NORMAL;

    /** 是否达到「核心」门槛。规则只认这一档，避免阈值散落各处。 */
    public boolean isCritical() {
        return this == CRITICAL;
    }
}
