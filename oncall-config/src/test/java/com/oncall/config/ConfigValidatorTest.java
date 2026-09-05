package com.oncall.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置校验器测试。
 *
 * <p>重点是「非法值必须被拒之门外」——校验发生在写入时而不是读取时，
 * 否则系统会带着一份坏配置跑一段时间才炸。
 */
class ConfigValidatorTest {

    private ConfigValidator validator;

    @BeforeEach
    void setUp() {
        validator = new ConfigValidator(OnCallConfigRegistry.create());
    }

    @Test
    @DisplayName("未声明的键一律拒绝，杜绝拼写错误的键被静默写入")
    void rejectsUndeclaredKey() {
        ValidationResult r = validator.validate("retrieval.topN", "5", true);
        assertThat(r.valid()).isFalse();
        assertThat(r.message()).contains("未声明");
    }

    @Test
    @DisplayName("合法整数通过")
    void acceptsValidInt() {
        assertThat(validator.validate(OnCallConfigKeys.RETRIEVAL_TOP_N, "8", true).valid()).isTrue();
    }

    @Test
    @DisplayName("非数字写进 INT 配置被拒")
    void rejectsNonNumericForInt() {
        ValidationResult r = validator.validate(OnCallConfigKeys.RETRIEVAL_TOP_N, "很多", true);
        assertThat(r.valid()).isFalse();
        assertThat(r.message()).contains("无法解析");
    }

    @Test
    @DisplayName("超出上界被拒，且错误信息里带上允许范围")
    void rejectsAboveMax() {
        ValidationResult r = validator.validate(OnCallConfigKeys.RETRIEVAL_TOP_N, "999", true);
        assertThat(r.valid()).isFalse();
        assertThat(r.message()).contains("[1, 20]").contains("999");
    }

    @Test
    @DisplayName("低于下界被拒")
    void rejectsBelowMin() {
        assertThat(validator.validate(OnCallConfigKeys.RETRIEVAL_TOP_N, "0", true).valid()).isFalse();
    }

    @Test
    @DisplayName("边界值本身合法（含端点）")
    void acceptsBoundaryValues() {
        assertThat(validator.validate(OnCallConfigKeys.RETRIEVAL_TOP_N, "1", true).valid()).isTrue();
        assertThat(validator.validate(OnCallConfigKeys.RETRIEVAL_TOP_N, "20", true).valid()).isTrue();
    }

    @Test
    @DisplayName("embedding 批大小上界是 10：前端物理上填不出会导致灌库失败的 10000")
    void embeddingBatchSizeCannotExceedProviderLimit() {
        assertThat(validator.validate(OnCallConfigKeys.VECTOR_EMBEDDING_BATCH_SIZE, "10", true).valid()).isTrue();
        ValidationResult r = validator.validate(OnCallConfigKeys.VECTOR_EMBEDDING_BATCH_SIZE, "10000", true);
        assertThat(r.valid()).isFalse();
        assertThat(r.message()).contains("[1, 10]");
    }

    @Test
    @DisplayName("枚举配置只接受声明过的取值")
    void rejectsValueOutsideAllowedSet() {
        ValidationResult r = validator.validate(OnCallConfigKeys.AUTONOMY_LEVEL, "FULL_AUTO", true);
        assertThat(r.valid()).isFalse();
        assertThat(r.message()).contains("SHADOW").contains("FULL_AUTO");
    }

    @Test
    @DisplayName("枚举配置接受声明过的取值")
    void acceptsDeclaredEnumValue() {
        assertThat(validator.validate(OnCallConfigKeys.AUTONOMY_LEVEL, "ASSIST", true).valid()).isTrue();
    }

    @Test
    @DisplayName("布尔值拼写错误必须被拒——不能静默当成 false")
    void rejectsMisspelledBoolean() {
        // Boolean.parseBoolean("ture") 会返回 false，那等于把拼写错误变成「关闭开关」
        assertThat(validator.validate(OnCallConfigKeys.RETRIEVAL_RERANK_ENABLED, "ture", true).valid()).isFalse();
        assertThat(validator.validate(OnCallConfigKeys.RETRIEVAL_RERANK_ENABLED, "true", true).valid()).isTrue();
        assertThat(validator.validate(OnCallConfigKeys.RETRIEVAL_RERANK_ENABLED, "FALSE", true).valid()).isTrue();
    }

    @Test
    @DisplayName("时长格式非法被拒")
    void rejectsBadDuration() {
        assertThat(validator.validate(OnCallConfigKeys.TICKET_APPROVAL_TIMEOUT, "十五分钟", true).valid()).isFalse();
        assertThat(validator.validate(OnCallConfigKeys.TICKET_APPROVAL_TIMEOUT, "15m", true).valid()).isTrue();
    }

    @Test
    @DisplayName("空值被拒——恢复默认必须走 reset 通道，不能写空串")
    void rejectsBlank() {
        assertThat(validator.validate(OnCallConfigKeys.RETRIEVAL_TOP_N, "  ", true).valid()).isFalse();
    }

    @Test
    @DisplayName("前端通道无法写 BACKEND_ONLY 项，且错误信息不暴露该键是否存在")
    void uiChannelCannotWriteBackendOnly() {
        ValidationResult r = validator.validate(OnCallConfigKeys.FALLBACK_INJECTION_BLOCK_WRITE, "false", true);
        assertThat(r.valid()).isFalse();
        assertThat(r.message()).doesNotContain("兜底").doesNotContain("BACKEND_ONLY");
    }

    @Test
    @DisplayName("后端通道可以写 BACKEND_ONLY 项（兜底逻辑自身需要调整）")
    void backendChannelCanWriteBackendOnly() {
        assertThat(validator.validate(OnCallConfigKeys.FALLBACK_INJECTION_BLOCK_WRITE, "true", false).valid()).isTrue();
    }

    @Test
    @DisplayName("DDL 绑定项对前端不可写：向量维度不是「配置」而是「迁移」")
    void uiChannelCannotWriteDimension() {
        assertThat(validator.validate(OnCallConfigKeys.VECTOR_DIMENSION, "2048", true).valid()).isFalse();
    }
}
