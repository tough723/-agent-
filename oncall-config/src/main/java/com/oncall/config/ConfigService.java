package com.oncall.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置读取与变更的统一入口。
 *
 * <p>业务代码**只应该**通过本类读配置，不应该自己读 yml 或环境变量。
 * 这是「配置不再散落各处」这条约束的落地点——建议用 ArchUnit 守住：
 * 除本模块外，禁止出现 {@code @Value} 与 {@code System.getenv}。
 *
 * <p>线程安全：读取走不可变快照 + volatile 引用替换，不加读锁；
 * 写入走 store 的并发保证，写后重建快照。
 */
public final class ConfigService {

    private final ConfigRegistry registry;
    private final ConfigStore store;
    private final ConfigValidator validator;
    private final ConfigAuditLog auditLog;

    private volatile ConfigSnapshot cached;
    private volatile long cachedRevision = -1L;

    public ConfigService(ConfigRegistry registry, ConfigStore store, ConfigAuditLog auditLog) {
        this.registry = registry;
        this.store = store;
        this.auditLog = auditLog;
        this.validator = new ConfigValidator(registry);
    }

    // ---------------------------------------------------------------- 读取

    /** 生效值：显式覆盖值优先，否则用声明里的默认值。 */
    public String get(String key) {
        ConfigSpec spec = registry.require(key);
        return store.get(key).orElse(spec.defaultValue());
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
        return parsed == null ? List.of() : (List<String>) parsed;
    }

    /**
     * 取一份一致的快照。仅当 store 修订号变化时才重建，
     * 所以高频调用（每次请求一次）不会有额外开销。
     */
    public ConfigSnapshot snapshot() {
        long current = store.revision();
        ConfigSnapshot local = cached;
        if (local != null && cachedRevision == current) {
            return local;
        }
        Map<String, String> effective = new LinkedHashMap<>();
        for (ConfigSpec spec : registry.all()) {
            effective.put(spec.key(), store.get(spec.key()).orElse(spec.defaultValue()));
        }
        ConfigSnapshot next = new ConfigSnapshot(effective, current, System.currentTimeMillis());
        this.cached = next;
        this.cachedRevision = current;
        return next;
    }

    /** 强制丢弃快照缓存。配置中心推送变更后调用。 */
    public void refresh() {
        this.cached = null;
        this.cachedRevision = -1L;
    }

    // ---------------------------------------------------------------- 变更

    /**
     * 写入一个配置值。校验不通过时**不写、不审计**，只返回原因。
     *
     * @param fromUi 是否来自前端通道。为 true 时拒绝写 BACKEND_ONLY 项。
     *               后端内部调整（如兜底逻辑）传 false。
     */
    public ValidationResult set(String key, String rawValue, String operator,
                                String reason, boolean fromUi) {
        ValidationResult vr = validator.validate(key, rawValue, fromUi);
        if (!vr.valid()) {
            return vr;
        }
        ConfigSpec spec = registry.require(key);
        String normalized = rawValue.trim();
        String oldEffective = get(key);
        if (normalized.equals(oldEffective)) {
            // 值没变，不写不审计——避免「反复点保存」制造审计噪音
            return ValidationResult.passed();
        }
        store.put(key, normalized);
        auditLog.record(new ConfigChange(key, oldEffective, normalized, operator, reason,
                spec.tier(), System.currentTimeMillis()));
        refresh();
        return ValidationResult.passed();
    }

    /** 恢复到声明的默认值（即被冻结的基线值）。 */
    public ValidationResult reset(String key, String operator, String reason, boolean fromUi) {
        if (!registry.contains(key)) {
            return ValidationResult.rejected("未声明的配置键：" + key);
        }
        ConfigSpec spec = registry.require(key);
        if (fromUi && spec.tier() == ConfigTier.BACKEND_ONLY) {
            return ValidationResult.rejected("配置键不存在或不可编辑：" + key);
        }
        String oldEffective = get(key);
        if (oldEffective.equals(spec.defaultValue())) {
            return ValidationResult.passed();
        }
        store.remove(key);
        auditLog.record(new ConfigChange(key, oldEffective, null, operator, reason,
                spec.tier(), System.currentTimeMillis()));
        refresh();
        return ValidationResult.passed();
    }

    // ---------------------------------------------------------------- 前端视图

    /**
     * 给前端渲染表单用的视图：声明 + 当前生效值 + 是否被改过。
     * BACKEND_ONLY 的项**不出现在返回值里**。
     */
    public List<ConfigView> viewsForUi() {
        List<ConfigView> out = new ArrayList<>();
        for (ConfigSpec spec : registry.visibleInUi()) {
            String effective = get(spec.key());
            out.add(new ConfigView(spec, effective, !effective.equals(spec.defaultValue())));
        }
        return out;
    }

    /**
     * 一个配置项在前端的呈现。
     *
     * @param spec           声明（含类型、边界、说明）
     * @param effectiveValue 当前生效值
     * @param overridden     是否已被改离默认值——前端应高亮，提示「这不是基线值」
     */
    public record ConfigView(ConfigSpec spec, String effectiveValue, boolean overridden) {
    }
}
