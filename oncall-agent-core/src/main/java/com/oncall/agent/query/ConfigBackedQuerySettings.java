package com.oncall.agent.query;

import com.oncall.config.ConfigService;
import com.oncall.config.OnCallConfigKeys;

import java.util.Objects;

/**
 * 生产用的实现：<b>每次调用都回配置读</b>。
 *
 * <p>这两个键是 {@code RUNTIME_HOT}。构造时取一次快照看起来更省，
 * 但那样"运维在前端改了阈值"就不会生效——
 * 而界面上它会显示成已保存、已生效。<b>假热更新比不能热更新更糟</b>，
 * 因为它让人以为自己改了。
 */
public final class ConfigBackedQuerySettings implements QuerySettings {

    private final ConfigService config;

    public ConfigBackedQuerySettings(ConfigService config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean rewriteEnabled() {
        return config.getBoolean(OnCallConfigKeys.QUERY_REWRITE_ENABLED);
    }

    @Override
    public double rewriteMinConfidence() {
        return config.getDouble(OnCallConfigKeys.QUERY_REWRITE_MIN_CONFIDENCE);
    }
}
