package com.oncall.config;

import java.util.Map;
import java.util.Optional;

/**
 * 配置持久化端口。
 *
 * <p>只定义「覆盖值」的存取——默认值不在这里，在 {@link ConfigSpec} 里。
 * 这样「哪些参数被显式改过」一目了然，也便于把配置恢复到出厂基线。
 *
 * <p>实现建议：
 * <ul>
 *   <li>{@code JdbcConfigStore}——第一期用这个，一张 {@code app_config} 表足够；</li>
 *   <li>配置中心适配器——多实例部署时用于热推送，监听变更后调用
 *       {@link ConfigService#refresh()}。</li>
 * </ul>
 */
public interface ConfigStore {

    Optional<String> get(String key);

    /** 所有被显式设置过的键值（不含默认值）。 */
    Map<String, String> getAll();

    void put(String key, String value);

    void remove(String key);

    /**
     * 单调递增的修订号。用于：
     * <ul>
     *   <li>让 {@link ConfigSnapshot} 能标明自己基于哪一版；</li>
     *   <li>多实例场景下判断本地缓存是否过期；</li>
     *   <li>审计里记录「这次请求用的是哪一版配置」。</li>
     * </ul>
     */
    long revision();
}
