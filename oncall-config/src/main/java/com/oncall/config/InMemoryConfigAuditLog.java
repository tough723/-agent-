package com.oncall.config;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内审计实现，用于测试与单机开发。生产换成持久化实现。
 */
public final class InMemoryConfigAuditLog implements ConfigAuditLog {

    private final List<ConfigChange> changes = new CopyOnWriteArrayList<>();

    @Override
    public void record(ConfigChange change) {
        if (change == null) {
            throw new IllegalArgumentException("change 不能为 null");
        }
        changes.add(change);
    }

    @Override
    public List<ConfigChange> history(String key) {
        List<ConfigChange> out = new ArrayList<>();
        for (ConfigChange c : changes) {
            if (c.key().equals(key)) {
                out.add(c);
            }
        }
        return out;
    }

    @Override
    public List<ConfigChange> recent(int limit) {
        List<ConfigChange> out = new ArrayList<>();
        for (int i = changes.size() - 1; i >= 0 && out.size() < limit; i--) {
            out.add(changes.get(i));
        }
        return out;
    }

    public int size() {
        return changes.size();
    }
}
