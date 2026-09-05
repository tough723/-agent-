package com.oncall.config;

import java.util.List;

/**
 * 配置变更审计端口。实现建议与工具审计共用一套持久化设施。
 */
public interface ConfigAuditLog {

    void record(ConfigChange change);

    /** 某个键的完整变更历史，按时间正序。 */
    List<ConfigChange> history(String key);

    /** 最近 n 条变更，按时间倒序。用于前端「最近改动」面板。 */
    List<ConfigChange> recent(int limit);
}
