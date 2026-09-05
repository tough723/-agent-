package com.oncall.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigServiceTest {

    private ConfigStore store;
    private InMemoryConfigAuditLog audit;
    private ConfigService service;

    @BeforeEach
    void setUp() {
        store = new InMemoryConfigStore();
        audit = new InMemoryConfigAuditLog();
        service = new ConfigService(OnCallConfigRegistry.create(), store, audit);
    }

    @Test
    @DisplayName("未设置覆盖值时返回冻结的默认值")
    void returnsDeclaredDefault() {
        assertThat(service.getInt(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEqualTo(5);
        assertThat(service.getInt(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE)).isEqualTo(20);
        assertThat(service.getInt(OnCallConfigKeys.RETRIEVAL_RRF_K)).isEqualTo(60);
        assertThat(service.getDouble(OnCallConfigKeys.RETRIEVAL_SIMILARITY_THRESHOLD)).isEqualTo(0.5);
    }

    @Test
    @DisplayName("类型化读取：时长、布尔、字符串列表")
    void typedAccessors() {
        assertThat(service.getDuration(OnCallConfigKeys.TICKET_APPROVAL_TIMEOUT))
                .isEqualTo(Duration.ofMinutes(15));
        assertThat(service.getDuration(OnCallConfigKeys.AGENT_TOOL_STEP_TIMEOUT))
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(service.getBoolean(OnCallConfigKeys.RETRIEVAL_RERANK_ENABLED)).isFalse();
        assertThat(service.getStringList(OnCallConfigKeys.FALLBACK_MODEL_FAILOVER_CHAIN))
                .containsExactly("deepseek-v4-flash", "deepseek-v4-pro");
    }

    @Test
    @DisplayName("读取未声明的键直接抛错，不静默返回 null")
    void unknownKeyThrows() {
        assertThatThrownBy(() -> service.get("retrieval.topN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未声明");
    }

    @Test
    @DisplayName("设置合法值后生效")
    void setAppliesValue() {
        ValidationResult r = service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "召回不足", true);
        assertThat(r.valid()).isTrue();
        assertThat(service.getInt(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEqualTo(8);
    }

    @Test
    @DisplayName("设置非法值：不写入、不产生审计记录")
    void invalidSetWritesNothing() {
        ValidationResult r = service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "999", "alice", "试试", true);
        assertThat(r.valid()).isFalse();
        assertThat(service.getInt(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEqualTo(5);
        assertThat(audit.size()).isZero();
    }

    @Test
    @DisplayName("写入相同值不产生审计噪音")
    void settingSameValueIsNotAudited() {
        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "5", "alice", "重复点击", true);
        assertThat(audit.size()).isZero();
    }

    @Test
    @DisplayName("审计记录含旧值、新值、操作人、理由与分级")
    void auditCapturesFullContext() {
        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "召回不足", true);

        List<ConfigChange> history = audit.history(OnCallConfigKeys.RETRIEVAL_TOP_N);
        assertThat(history).hasSize(1);
        ConfigChange c = history.get(0);
        assertThat(c.oldValue()).isEqualTo("5");
        assertThat(c.newValue()).isEqualTo("8");
        assertThat(c.operator()).isEqualTo("alice");
        assertThat(c.reason()).isEqualTo("召回不足");
        assertThat(c.tier()).isEqualTo(ConfigTier.RUNTIME_HOT);
        assertThat(c.isReset()).isFalse();
        assertThat(c.effectiveValueChanged()).isTrue();
    }

    @Test
    @DisplayName("reset 恢复默认值，审计里 newValue 为 null 表示恢复")
    void resetRestoresDefault() {
        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "调大", true);
        ValidationResult r = service.reset(OnCallConfigKeys.RETRIEVAL_TOP_N, "bob", "回归基线", true);

        assertThat(r.valid()).isTrue();
        assertThat(service.getInt(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEqualTo(5);

        List<ConfigChange> history = audit.history(OnCallConfigKeys.RETRIEVAL_TOP_N);
        assertThat(history).hasSize(2);
        assertThat(history.get(1).isReset()).isTrue();
        assertThat(history.get(1).newValue()).isNull();
        assertThat(history.get(1).oldValue()).isEqualTo("8");
    }

    @Test
    @DisplayName("已是默认值时 reset 不产生审计记录")
    void resetOnDefaultIsNoOp() {
        service.reset(OnCallConfigKeys.RETRIEVAL_TOP_N, "bob", "无操作", true);
        assertThat(audit.size()).isZero();
    }

    @Test
    @DisplayName("前端通道写 BACKEND_ONLY 被拒，后端通道可以")
    void tierEnforcedOnWrite() {
        assertThat(service.set(OnCallConfigKeys.FALLBACK_RULE_BASED_ENABLED, "false", "alice", "想关", true).valid())
                .isFalse();
        assertThat(service.getBoolean(OnCallConfigKeys.FALLBACK_RULE_BASED_ENABLED)).isTrue();

        assertThat(service.set(OnCallConfigKeys.FALLBACK_RULE_BASED_ENABLED, "false", "ops", "演练", false).valid())
                .isTrue();
        assertThat(service.getBoolean(OnCallConfigKeys.FALLBACK_RULE_BASED_ENABLED)).isFalse();
    }

    @Test
    @DisplayName("快照反映最新值，且修订号随写入递增")
    void snapshotTracksRevision() {
        long before = service.snapshot().revision();
        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "调大", true);
        ConfigSnapshot after = service.snapshot();

        assertThat(after.revision()).isGreaterThan(before);
        assertThat(after.getInt(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEqualTo(8);
        // 同一份快照里其他键也读得到，保证一次请求内配置一致
        assertThat(after.getInt(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE)).isEqualTo(20);
    }

    @Test
    @DisplayName("无写入时重复取快照命中缓存，修订号不变")
    void snapshotIsCachedUntilRevisionChanges() {
        ConfigSnapshot first = service.snapshot();
        ConfigSnapshot second = service.snapshot();
        assertThat(second.revision()).isEqualTo(first.revision());
        assertThat(second).isSameAs(first);
    }

    @Test
    @DisplayName("refresh 后快照重建")
    void refreshRebuildsSnapshot() {
        ConfigSnapshot first = service.snapshot();
        store.put(OnCallConfigKeys.RETRIEVAL_TOP_N, "9");   // 绕过 service 直接改 store，模拟配置中心推送
        service.refresh();
        ConfigSnapshot rebuilt = service.snapshot();

        assertThat(rebuilt).isNotSameAs(first);
        assertThat(rebuilt.getInt(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEqualTo(9);
    }

    @Test
    @DisplayName("快照是不可变的：拿到的 map 改了不影响后续读取")
    void snapshotMapIsDefensiveCopy() {
        ConfigSnapshot s = service.snapshot();
        s.asMap().put(OnCallConfigKeys.RETRIEVAL_TOP_N, "999");
        assertThat(service.snapshot().getInt(OnCallConfigKeys.RETRIEVAL_TOP_N)).isEqualTo(5);
    }

    @Test
    @DisplayName("前端视图不含任何 BACKEND_ONLY 项")
    void viewsForUiExcludesBackendOnly() {
        List<ConfigService.ConfigView> views = service.viewsForUi();
        assertThat(views).isNotEmpty();
        assertThat(views).noneMatch(v -> v.spec().tier() == ConfigTier.BACKEND_ONLY);
        assertThat(views).extracting(v -> v.spec().key())
                .doesNotContain(OnCallConfigKeys.FALLBACK_RULE_BASED_ENABLED)
                .doesNotContain(OnCallConfigKeys.VECTOR_DIMENSION)
                .doesNotContain(OnCallConfigKeys.FALLBACK_INJECTION_BLOCK_WRITE);
    }

    @Test
    @DisplayName("前端视图标记哪些项已被改离基线值")
    void viewsForUiMarksOverridden() {
        assertThat(service.viewsForUi())
                .filteredOn(v -> v.spec().key().equals(OnCallConfigKeys.RETRIEVAL_TOP_N))
                .singleElement()
                .satisfies(v -> assertThat(v.overridden()).isFalse());

        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "调大", true);

        assertThat(service.viewsForUi())
                .filteredOn(v -> v.spec().key().equals(OnCallConfigKeys.RETRIEVAL_TOP_N))
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.overridden()).isTrue();
                    assertThat(v.effectiveValue()).isEqualTo("8");
                    assertThat(v.spec().defaultValue()).isEqualTo("5");
                });
    }
}
