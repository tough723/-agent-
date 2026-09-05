package com.oncall.config.admin;

import java.util.Optional;

/**
 * 待复核变更的存放端口。
 *
 * <p>定义为端口而不是直接用 Map：多实例部署时，A 实例发起的变更必须能被
 * B 实例复核，否则"找另一个人点确认"会变成"找另一个人去点同一台机器"。
 * 生产实现应当落在数据库（与 {@code config_audit_log} 同一套设施）。
 */
public interface PendingChangeStore {

    void put(PendingChange change);

    Optional<PendingChange> find(String id);

    /** 复核完成（通过或驳回）后移除，避免同一单被复核两次。 */
    void remove(String id);
}
