package com.oncall.config;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 进程内 {@link ConfigStore} 实现。
 *
 * <p>用于单元测试与单机开发。生产环境换成 JDBC 或配置中心实现——
 * 但注意它**不是**玩具：并发安全是实打实做了的，
 * 因为放权等级、kill switch 这类配置会在运行期被改。
 */
public final class InMemoryConfigStore implements ConfigStore {

    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final AtomicLong revision = new AtomicLong(0);

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(values.get(key));
    }

    @Override
    public Map<String, String> getAll() {
        return new LinkedHashMap<>(values);
    }

    @Override
    public void put(String key, String value) {
        if (key == null || value == null) {
            throw new IllegalArgumentException("key/value 不能为 null");
        }
        values.put(key, value);
        revision.incrementAndGet();
    }

    @Override
    public void remove(String key) {
        if (values.remove(key) != null) {
            revision.incrementAndGet();
        }
    }

    @Override
    public long revision() {
        return revision.get();
    }
}
