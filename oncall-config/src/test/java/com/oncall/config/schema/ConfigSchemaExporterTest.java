package com.oncall.config.schema;

import com.oncall.config.ConfigRegistry;
import com.oncall.config.ConfigService;
import com.oncall.config.ConfigSpec;
import com.oncall.config.ConfigTier;
import com.oncall.config.ConfigType;
import com.oncall.config.InMemoryConfigAuditLog;
import com.oncall.config.InMemoryConfigStore;
import com.oncall.config.OnCallConfigKeys;
import com.oncall.config.OnCallConfigRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigSchemaExporterTest {

    private ConfigService service;
    private ConfigSchemaExporter exporter;

    @BeforeEach
    void setUp() {
        service = new ConfigService(OnCallConfigRegistry.create(),
                new InMemoryConfigStore(), new InMemoryConfigAuditLog());
        exporter = new ConfigSchemaExporter(service);
    }

    @Test
    @DisplayName("导出的 JSON 括号与引号配平")
    void outputIsBalancedJson() {
        String json = exporter.exportForUi();
        assertThat(ConfigSchemaExporter.looksLikeBalancedJson(json))
                .as("JSON 结构不配平：\n%s", json)
                .isTrue();
    }

    @Test
    @DisplayName("导出的键集合与前端可见集合完全一致")
    void exportedKeysMatchVisibleSpecs() {
        assertThat(exporter.exportedKeys()).hasSameSizeAs(service.viewsForUi());
        assertThat(exporter.exportedKeys().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        service.viewsForUi().stream().map(v -> v.spec().key()).toList());
    }

    @Test
    @DisplayName("BACKEND_ONLY 的键名不出现在导出内容里——连键的存在都不该泄露给前端")
    void backendOnlyKeysNeverLeak() {
        String json = exporter.exportForUi();
        List<String> mustNotAppear = List.of(
                OnCallConfigKeys.FALLBACK_RULE_BASED_ENABLED,
                OnCallConfigKeys.FALLBACK_EMBEDDING_DEGRADE_TO_BM25,
                OnCallConfigKeys.FALLBACK_RERANKER_DEGRADE_TO_ORIGINAL_ORDER,
                OnCallConfigKeys.FALLBACK_MODEL_FAILOVER_CHAIN,
                OnCallConfigKeys.FALLBACK_INJECTION_BLOCK_WRITE,
                OnCallConfigKeys.VECTOR_DIMENSION,
                OnCallConfigKeys.VECTOR_DISTANCE_TYPE,
                OnCallConfigKeys.VECTOR_INDEX_TYPE
        );
        for (String key : mustNotAppear) {
            assertThat(json).as("后端专属配置泄露到前端 schema：%s", key).doesNotContain(key);
        }
        assertThat(json).doesNotContain("BACKEND_ONLY");
    }

    @Test
    @DisplayName("每个表单项都带前端渲染所需的字段")
    void itemsCarryRenderingMetadata() {
        String json = exporter.exportForUi();
        for (String field : List.of("\"key\"", "\"type\"", "\"tier\"", "\"description\"",
                "\"defaultValue\"", "\"effectiveValue\"", "\"overridden\"", "\"hotReloadable\"",
                "\"min\"", "\"max\"", "\"allowedValues\"", "\"migrationHint\"", "\"sensitive\"")) {
            assertThat(json).as("缺字段 %s", field).contains(field);
        }
    }

    @Test
    @DisplayName("可热改的配置项在前端是可编辑的")
    void hotItemsAreEditable() {
        assertThat(exporter.exportForUi()).contains("\"hotReloadable\": true");
    }

    @Test
    @DisplayName("迁移型配置带提示，前端据此提示「保存后不会立即生效」")
    void migrationItemsCarryHint() {
        assertThat(exporter.exportForUi())
                .contains("REQUIRES_MIGRATION")
                .contains("重建索引");
    }

    @Test
    @DisplayName("覆盖值会反映到 effectiveValue 与 overridden")
    void reflectsOverriddenValue() {
        service.set(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", "alice", "调大", true);
        String json = exporter.exportForUi();
        assertThat(json).contains("\"effectiveValue\": \"8\"");
        assertThat(json).contains("\"overridden\": true");
    }

    @Test
    @DisplayName("JSON 字符串转义：引号、反斜杠、换行都不能破坏结构")
    void escapesSpecialCharacters() {
        assertThat(ConfigSchemaExporter.jsonString("他说\"不行\""))
                .isEqualTo("\"他说\\\"不行\\\"\"");
        assertThat(ConfigSchemaExporter.jsonString("a\\b")).isEqualTo("\"a\\\\b\"");
        assertThat(ConfigSchemaExporter.jsonString("第一行\n第二行")).isEqualTo("\"第一行\\n第二行\"");
        assertThat(ConfigSchemaExporter.jsonString(null)).isEqualTo("null");

        // 带引号的描述不能把整份 schema 弄坏
        ConfigSpec tricky = ConfigSpec.builder("t.tricky", ConfigType.STRING, "v", ConfigTier.RUNTIME_HOT)
                .description("含\"引号\"与\\反斜杠")
                .build();
        ConfigService s = new ConfigService(new ConfigRegistry(List.of(tricky)),
                new InMemoryConfigStore(), new InMemoryConfigAuditLog());
        String json = new ConfigSchemaExporter(s).exportForUi();
        assertThat(ConfigSchemaExporter.looksLikeBalancedJson(json)).isTrue();
        assertThat(json).contains("含\\\"引号\\\"");
    }

    @Test
    @DisplayName("枚举配置导出合法取值列表，前端据此渲染下拉框")
    void enumValuesExported() {
        String json = exporter.exportForUi();
        assertThat(json).contains("SHADOW").contains("BOUNDED_AUTO");
        assertThat(json).contains("READ_ONLY");
    }
}
