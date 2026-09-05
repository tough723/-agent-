package com.oncall.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 某一时刻全部生效配置的不可变快照。
 *
 * <p><b>为什么需要它：</b>一次请求里如果多次分别读配置，中途有人改了配置，
 * 就会出现「同一次回答里前半段用 Top-5、后半段用 Top-10」这种不一致。
 * 快照保证**一次请求内配置是稳定的**，并且能标明自己基于哪一版（{@code revision}），
 * 便于事后复现「当时到底用的什么参数」。
 *
 * @param values           键 → 生效值（覆盖值优先，否则默认值）
 * @param revision         对应的 {@link ConfigStore#revision()}
 * @param capturedAtMillis 快照生成时间
 */
public record ConfigSnapshot(Map<String, String> values, long revision, long capturedAtMillis) {

    public ConfigSnapshot {
        values = Map.copyOf(values);
    }

    public String get(String key) {
        String v = values.get(key);
        if (v == null) {
            throw new IllegalArgumentException("快照中不存在配置键：" + key);
        }
        return v;
    }

    public int getInt(String key) {
        return Integer.parseInt(get(key).trim());
    }

    public long getLong(String key) {
        return Long.parseLong(get(key).trim());
    }

    public double getDouble(String key) {
        return Double.parseDouble(get(key).trim());
    }

    public boolean getBoolean(String key) {
        return "true".equalsIgnoreCase(get(key).trim());
    }

    /** 快照里的时长字面量转 {@link Duration}；解析失败视为配置损坏，直接抛。 */
    public Duration getDuration(String key) {
        Object parsed = ConfigType.DURATION.parse(get(key));
        if (!(parsed instanceof Duration d)) {
            throw new IllegalStateException("配置 " + key + " 的值不是合法时长：" + get(key));
        }
        return d;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String key) {
        Object parsed = ConfigType.STRING_LIST.parse(get(key));
        if (parsed == null) {
            return List.of();
        }
        return (List<String>) parsed;
    }

    public Map<String, String> asMap() {
        return new LinkedHashMap<>(values);
    }
}
