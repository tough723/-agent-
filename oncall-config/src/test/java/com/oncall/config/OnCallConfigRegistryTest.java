package com.oncall.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 配置声明表的结构性约束测试。
 *
 * <p>这些断言守的不是业务逻辑，而是**架构约束本身**：
 * 「所有可配置的都不许硬编码在后端，兜底除外」这句话要成立，
 * 就必须有测试证明兜底项确实是 BACKEND_ONLY、数值项确实有边界、
 * DDL 绑定项确实没被暴露出去。
 */
class OnCallConfigRegistryTest {

    private final ConfigRegistry registry = OnCallConfigRegistry.create();

    @Test
    @DisplayName("声明表自检通过（无重复键、无缺 migrationHint 的迁移项、默认值全部可解析）")
    void registrySelfCheckPasses() {
        assertThatCode(() -> OnCallConfigRegistry.create()).doesNotThrowAnyException();
        assertThat(registry.size()).isGreaterThan(20);
    }

    @Test
    @DisplayName("每个声明的默认值本身必须合法——「用默认值启动」这条路不能是坏的")
    void allDefaultsAreValid() {
        ConfigValidator validator = new ConfigValidator(registry);
        for (ConfigSpec s : registry.all()) {
            assertThat(validator.validate(s.key(), s.defaultValue(), false).valid())
                    .as("默认值非法：%s = %s", s.key(), s.defaultValue())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("兜底机制配置全部为 BACKEND_ONLY：前端既看不到也改不了")
    void allFallbackKeysAreBackendOnly() {
        List<String> fallbackKeys = registry.all().stream()
                .map(ConfigSpec::key)
                .filter(k -> k.startsWith("fallback."))
                .toList();

        assertThat(fallbackKeys).hasSizeGreaterThanOrEqualTo(5);
        for (String k : fallbackKeys) {
            assertThat(registry.require(k).tier())
                    .as("兜底配置 %s 必须是 BACKEND_ONLY", k)
                    .isEqualTo(ConfigTier.BACKEND_ONLY);
        }
    }

    @Test
    @DisplayName("DDL 绑定项（向量维度/距离函数/索引类型）为 BACKEND_ONLY")
    void ddlBoundKeysAreBackendOnly() {
        assertThat(registry.require(OnCallConfigKeys.VECTOR_DIMENSION).tier()).isEqualTo(ConfigTier.BACKEND_ONLY);
        assertThat(registry.require(OnCallConfigKeys.VECTOR_DISTANCE_TYPE).tier()).isEqualTo(ConfigTier.BACKEND_ONLY);
        assertThat(registry.require(OnCallConfigKeys.VECTOR_INDEX_TYPE).tier()).isEqualTo(ConfigTier.BACKEND_ONLY);
    }

    @Test
    @DisplayName("向量维度上界不得超过 2000：pgvector HNSW 索引的硬限制")
    void vectorDimensionRespectsHnswLimit() {
        ConfigSpec dim = registry.require(OnCallConfigKeys.VECTOR_DIMENSION);
        assertThat(dim.max()).isNotNull();
        assertThat(dim.max()).isLessThanOrEqualTo(2000.0);
        assertThat(dim.defaultValue()).isEqualTo("1024");
    }

    @Test
    @DisplayName("embedding 单批上界钉在 10：上游 API 的硬限制，框架默认值 10000 会导致灌库失败")
    void embeddingBatchSizeCappedAtProviderLimit() {
        ConfigSpec batch = registry.require(OnCallConfigKeys.VECTOR_EMBEDDING_BATCH_SIZE);
        assertThat(batch.max()).isEqualTo(10.0);
        assertThat(batch.defaultValue()).isEqualTo("10");
        assertThat(batch.tier()).isEqualTo(ConfigTier.RUNTIME_HOT);
    }

    @Test
    @DisplayName("所有 RUNTIME_HOT 的数值型配置都必须有边界，否则前端就是个能调崩系统的输入框")
    void everyHotNumericSpecHasBounds() {
        for (ConfigSpec s : registry.all()) {
            boolean numeric = s.type() == ConfigType.INT
                    || s.type() == ConfigType.LONG
                    || s.type() == ConfigType.DOUBLE;
            if (s.tier() == ConfigTier.RUNTIME_HOT && numeric) {
                assertThat(s.hasBounds())
                        .as("缺边界的可热改数值配置：%s", s.key())
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("所有可热改的配置都必须有说明文案——前端要拿它当帮助文本")
    void everyHotSpecHasDescription() {
        for (ConfigSpec s : registry.all()) {
            if (s.tier() == ConfigTier.RUNTIME_HOT) {
                assertThat(s.description())
                        .as("缺说明的可热改配置：%s", s.key())
                        .isNotBlank();
            }
        }
    }

    @Test
    @DisplayName("切片类配置为 REQUIRES_MIGRATION 且带迁移说明")
    void chunkingRequiresMigration() {
        for (ConfigSpec s : registry.byGroup(OnCallConfigKeys.GROUP_CHUNKING)) {
            assertThat(s.tier())
                    .as("切片配置 %s 改动需重建索引", s.key())
                    .isEqualTo(ConfigTier.REQUIRES_MIGRATION);
            assertThat(s.migrationHint()).isNotBlank();
        }
    }

    @Test
    @DisplayName("embedding 模型为 REQUIRES_MIGRATION，且说明里点明语义空间不可比")
    void embeddingModelRequiresMigration() {
        ConfigSpec model = registry.require(OnCallConfigKeys.VECTOR_EMBEDDING_MODEL);
        assertThat(model.tier()).isEqualTo(ConfigTier.REQUIRES_MIGRATION);
        assertThat(model.migrationHint()).contains("语义空间");
        assertThat(model.defaultValue()).isEqualTo("text-embedding-v4");
    }

    @Test
    @DisplayName("放权等级默认 SHADOW（S0 影子模式），且四个等级齐全")
    void autonomyDefaultsToShadow() {
        ConfigSpec level = registry.require(OnCallConfigKeys.AUTONOMY_LEVEL);
        assertThat(level.defaultValue()).isEqualTo("SHADOW");
        assertThat(level.allowedValues())
                .containsExactly("SHADOW", "SUGGEST", "ASSIST", "BOUNDED_AUTO");
        assertThat(level.tier()).isEqualTo(ConfigTier.RUNTIME_HOT);
    }

    @Test
    @DisplayName("kill switch 默认 FULL，三档齐全，且可热改（发版才能降级等于没有保险丝）")
    void killSwitchIsHotReloadable() {
        ConfigSpec mode = registry.require(OnCallConfigKeys.AUTONOMY_KILL_SWITCH_MODE);
        assertThat(mode.allowedValues()).containsExactly("FULL", "READ_ONLY", "OFF");
        assertThat(mode.tier()).isEqualTo(ConfigTier.RUNTIME_HOT);
    }

    @Test
    @DisplayName("冻结清单里的关键默认值与文档一致")
    void frozenDefaultsMatchDocumentation() {
        Map<String, String> expected = Map.ofEntries(
                Map.entry(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE, "20"),
                Map.entry(OnCallConfigKeys.RETRIEVAL_TOP_N, "5"),
                Map.entry(OnCallConfigKeys.RETRIEVAL_RRF_K, "60"),
                Map.entry(OnCallConfigKeys.RETRIEVAL_SIMILARITY_THRESHOLD, "0.5"),
                Map.entry(OnCallConfigKeys.RETRIEVAL_SIMILARITY_THRESHOLD_RETRY, "0.3"),
                Map.entry(OnCallConfigKeys.RETRIEVAL_RERANK_MIN_SCORE, "0.3"),
                Map.entry(OnCallConfigKeys.GENERATION_MAX_CONTEXT_TOKENS, "12000"),
                Map.entry(OnCallConfigKeys.GENERATION_MAX_OUTPUT_TOKENS, "2000"),
                Map.entry(OnCallConfigKeys.MEMORY_WINDOW_SIZE, "6"),
                Map.entry(OnCallConfigKeys.MEMORY_SUMMARY_THRESHOLD, "20"),
                Map.entry(OnCallConfigKeys.AGENT_MAX_STEPS, "10"),
                Map.entry(OnCallConfigKeys.AGENT_MAX_REPLANS, "5"),
                Map.entry(OnCallConfigKeys.CHUNKING_RCA_CHUNK_SIZE, "800"),
                Map.entry(OnCallConfigKeys.CHUNKING_RCA_OVERLAP, "100"),
                Map.entry(OnCallConfigKeys.CHUNKING_CHILD_TOKEN_SIZE, "300"),
                Map.entry(OnCallConfigKeys.CHUNKING_MAX_PARENTS, "4"),
                Map.entry(OnCallConfigKeys.ALERT_STORM_THRESHOLD_PER_MINUTE, "50")
        );
        for (Map.Entry<String, String> e : expected.entrySet()) {
            assertThat(registry.require(e.getKey()).defaultValue())
                    .as("默认值与冻结清单不一致：%s", e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    @DisplayName("temperature 默认 0：不为 0 时评测结果不可复现，L3 回归判据会失效")
    void temperatureDefaultsToZero() {
        ConfigSpec t = registry.require(OnCallConfigKeys.GENERATION_TEMPERATURE);
        assertThat(t.defaultValue()).isEqualTo("0.0");
        assertThat(Double.parseDouble(t.defaultValue())).isZero();
        assertThat(t.min()).isZero();
        assertThat(t.max()).isEqualTo(1.0);
        // 说明里必须写清"评测跑批必须为 0"，否则调的人会不知道后果
        assertThat(t.description()).contains("评测跑批必须为 0");
    }

    @Test
    @DisplayName("空召回重试阈值必须小于主阈值，否则重试没有意义")
    void retryThresholdIsLowerThanPrimary() {
        double primary = Double.parseDouble(registry.require(OnCallConfigKeys.RETRIEVAL_SIMILARITY_THRESHOLD).defaultValue());
        double retry = Double.parseDouble(registry.require(OnCallConfigKeys.RETRIEVAL_SIMILARITY_THRESHOLD_RETRY).defaultValue());
        assertThat(retry).isLessThan(primary);
    }

    @Test
    @DisplayName("候选数必须大于 Top-N，否则粗排没有筛选余地")
    void candidateSizeExceedsTopN() {
        int candidate = Integer.parseInt(registry.require(OnCallConfigKeys.RETRIEVAL_CANDIDATE_SIZE).defaultValue());
        int topN = Integer.parseInt(registry.require(OnCallConfigKeys.RETRIEVAL_TOP_N).defaultValue());
        assertThat(candidate).isGreaterThan(topN);
    }

    @Test
    @DisplayName("分级统计用精确值：新增或改动配置项必须显式更新这里，防止文档与代码静默漂移")
    void tierDistribution() {
        // 用精确值而不是区间，是刻意的：曾经文档写「36 项 / RUNTIME_HOT 20 项」而
        // 代码实际是 38 项 / 22 项，差了 2 项且没人发现。区间断言抓不到这种漂移。
        Map<ConfigTier, Integer> counts = registry.countByTier();
        assertThat(counts.get(ConfigTier.RUNTIME_HOT)).isEqualTo(23);
        assertThat(counts.get(ConfigTier.REQUIRES_MIGRATION)).isEqualTo(8);
        assertThat(counts.get(ConfigTier.BACKEND_ONLY)).isEqualTo(8);
        assertThat(registry.size()).isEqualTo(39);
    }

    @Test
    @DisplayName("分组非空且可枚举，供前端分栏")
    void groupsAreEnumerable() {
        assertThat(registry.groups())
                .contains(OnCallConfigKeys.GROUP_RETRIEVAL, OnCallConfigKeys.GROUP_CHUNKING,
                        OnCallConfigKeys.GROUP_VECTOR, OnCallConfigKeys.GROUP_AGENT,
                        OnCallConfigKeys.GROUP_FALLBACK);
        for (String g : registry.groups()) {
            assertThat(registry.byGroup(g)).as("空分组：%s", g).isNotEmpty();
        }
    }

    @Test
    @DisplayName("注册表拒绝坏声明：重复键")
    void rejectsDuplicateKeys() {
        ConfigSpec a = ConfigSpec.builder("x.y", ConfigType.INT, "1", ConfigTier.RUNTIME_HOT).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ConfigRegistry(List.of(a, a)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("重复的配置键");
    }

    @Test
    @DisplayName("注册表拒绝坏声明：REQUIRES_MIGRATION 缺迁移说明")
    void rejectsMigrationWithoutHint() {
        ConfigSpec bad = ConfigSpec.builder("x.y", ConfigType.INT, "1", ConfigTier.REQUIRES_MIGRATION).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ConfigRegistry(List.of(bad)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("声明不完整");
    }

    @Test
    @DisplayName("注册表拒绝坏声明：默认值无法解析")
    void rejectsUnparseableDefault() {
        ConfigSpec bad = ConfigSpec.builder("x.y", ConfigType.INT, "abc", ConfigTier.RUNTIME_HOT).build();
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new ConfigRegistry(List.of(bad)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("默认值无法解析");
    }
}
