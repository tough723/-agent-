package com.oncall.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 所有配置项声明的注册表——**配置的唯一事实来源**。
 *
 * <p>不可变。构造时做一次自检：重复键、{@link ConfigSpec#declarationIsComplete()}
 * 不通过的声明，直接在启动阶段抛错，而不是等到运行时才发现某个配置项没法用。
 *
 * <p>自检是刻意的严格：这个模块存在的意义就是「配置不再散落各处」，
 * 如果它自己允许坏声明通过，就失去了价值。
 */
public final class ConfigRegistry {

    private final Map<String, ConfigSpec> specs;

    public ConfigRegistry(Collection<ConfigSpec> declarations) {
        Map<String, ConfigSpec> m = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        for (ConfigSpec s : declarations) {
            if (m.containsKey(s.key())) {
                problems.add("重复的配置键：" + s.key());
                continue;
            }
            if (!s.declarationIsComplete()) {
                problems.add("声明不完整（REQUIRES_MIGRATION 缺 migrationHint，"
                        + "或 ENUM_STRING 缺 allowedValues）：" + s.key());
                continue;
            }
            // 默认值本身必须合法，否则「用默认值启动」这条路就是坏的
            Object parsed = s.type().parse(s.defaultValue());
            if (parsed == null) {
                problems.add("默认值无法解析为 " + s.type() + "：" + s.key()
                        + " = " + s.defaultValue());
                continue;
            }
            m.put(s.key(), s);
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException("配置注册表自检失败：\n  " + String.join("\n  ", problems));
        }
        this.specs = Collections.unmodifiableMap(m);
    }

    public ConfigSpec require(String key) {
        ConfigSpec s = specs.get(key);
        if (s == null) {
            throw new IllegalArgumentException("未声明的配置键：" + key
                    + "（所有配置项必须先在注册表声明，不允许直接读写）");
        }
        return s;
    }

    public Optional<ConfigSpec> find(String key) {
        return Optional.ofNullable(specs.get(key));
    }

    public boolean contains(String key) {
        return specs.containsKey(key);
    }

    public List<ConfigSpec> all() {
        return List.copyOf(specs.values());
    }

    /** 前端应该展示的配置项。BACKEND_ONLY 不出现在这个列表里。 */
    public List<ConfigSpec> visibleInUi() {
        List<ConfigSpec> out = new ArrayList<>();
        for (ConfigSpec s : specs.values()) {
            if (s.visibleInUi()) {
                out.add(s);
            }
        }
        return out;
    }

    public List<ConfigSpec> byGroup(String group) {
        List<ConfigSpec> out = new ArrayList<>();
        for (ConfigSpec s : specs.values()) {
            if (group.equals(s.group())) {
                out.add(s);
            }
        }
        return out;
    }

    /** 前端表单的分栏顺序。 */
    public List<String> groups() {
        List<String> out = new ArrayList<>();
        for (ConfigSpec s : specs.values()) {
            if (!out.contains(s.group())) {
                out.add(s.group());
            }
        }
        return out;
    }

    public int size() {
        return specs.size();
    }

    /** 按分级统计，用于在文档/CI 里核对「有多少参数被外置了」。 */
    public Map<ConfigTier, Integer> countByTier() {
        Map<ConfigTier, Integer> out = new LinkedHashMap<>();
        for (ConfigTier t : ConfigTier.values()) {
            out.put(t, 0);
        }
        for (ConfigSpec s : specs.values()) {
            out.merge(s.tier(), 1, Integer::sum);
        }
        return out;
    }
}
