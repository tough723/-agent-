package com.oncall.eval;

import com.oncall.agent.query.Intent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 录制的序列化。
 *
 * <p>这一层守的是<b>出处（provenance）不许缺失</b>。
 * 一份只写了"INT-011 -> EXECUTE"的录制，无法回答
 * "当时用的是哪一版 prompt、哪个模型、什么配置"，
 * 于是 v1 与 v2 的对比会悄悄连模型和配置一起比——
 * 得出的结论没有意义，而且看不出来它没有意义。
 * 所以这里所有缺字段的用例断言的都是<b>必须抛</b>，不是"给个默认值"。
 */
@DisplayName("IntentRunRecording：录制的序列化与出处校验")
class IntentRunRecordingTest {

    private static final RunProvenance PROV =
            new RunProvenance("deepseek-v4-flash", "v1", true, 0.7, 1_700_000_000_000L);

    private static final RecordedIntent R1 = new RecordedIntent(
            "INT-001", Intent.EXECUTE, true, Intent.EXPLAIN_ALERT, false, 0.86, false);
    private static final RecordedIntent R2 = new RecordedIntent(
            "INT-045", Intent.OUT_OF_SCOPE, false, null, false, 0.2, true);

    @Test
    @DisplayName("YAML 往返：所有字段一个不少，包括 llmIntent 为 null 的那种")
    void roundTripsThroughYaml() {
        IntentRunRecording original = new IntentRunRecording(PROV, List.of(R1, R2));

        String yaml = original.toYaml();
        IntentRunRecording back = IntentRunRecording.fromYaml(yaml);

        assertThat(back.provenance()).isEqualTo(PROV);
        assertThat(back.results()).containsExactly(R1, R2);
        assertThat(back.forCase("INT-045").orElseThrow().llmIntent())
                .as("模型没给出闭集内的标签时是 null，不是 EXECUTE 也不是空串")
                .isNull();
        assertThat(back.forCase("INT-001").orElseThrow().intentFromRule()).isTrue();
    }

    @Test
    @DisplayName("YAML 是人读的：出处写在头部")
    void yamlIsHumanReadable() {
        String yaml = new IntentRunRecording(PROV, List.of(R1)).toYaml();

        assertThat(yaml).contains("model: deepseek-v4-flash").contains("promptVersion: v1");
        assertThat(yaml).contains("caseId: INT-001").contains("intent: EXECUTE");
    }

    @Test
    @DisplayName("缺 provenance → 抛")
    void provenanceIsMandatory() {
        assertThatThrownBy(() -> IntentRunRecording.fromYaml("results:\n  - {caseId: A}\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provenance");
    }

    @Test
    @DisplayName("provenance 缺 model → 抛，不给默认值")
    void missingModelIsRejected() {
        String yaml = """
                provenance:
                  promptVersion: v1
                  rewriteEnabled: true
                  rewriteMinConfidence: 0.7
                  recordedAtMillis: 1
                results:
                  - {caseId: A, intent: EXECUTE, intentFromRule: true, rewritten: false, confidence: 0.9, degraded: false}
                """;
        assertThatThrownBy(() -> IntentRunRecording.fromYaml(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("model")
                .hasMessageContaining("刻意不给默认值");
    }

    @Test
    @DisplayName("空录制 → 抛（空录制会让所有指标的分母变成 0）")
    void emptyResultsAreRejected() {
        assertThatThrownBy(() -> new IntentRunRecording(PROV, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("一条结果都没有");
    }

    @Test
    @DisplayName("意图标签落在闭集外 → 抛")
    void unknownIntentLabelsAreRejected() {
        String yaml = """
                provenance: {model: m, promptVersion: v1, rewriteEnabled: true, rewriteMinConfidence: 0.7, recordedAtMillis: 1}
                results:
                  - {caseId: A, intent: RESTART_ALL, intentFromRule: false, rewritten: false, confidence: 0.9, degraded: false}
                """;
        assertThatThrownBy(() -> IntentRunRecording.fromYaml(yaml))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("RESTART_ALL");
    }

    @Test
    @DisplayName("出处字段的形状校验")
    void provenanceValidatesItsShape() {
        assertThatThrownBy(() -> new RunProvenance(" ", "v1", true, 0.7, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("model");
        assertThatThrownBy(() -> new RunProvenance("m", null, true, 0.7, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("promptVersion");
        assertThatThrownBy(() -> new RunProvenance("m", "v1", true, 1.5, 1L))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[0,1]");
        assertThat(PROV.summary()).contains("deepseek-v4-flash").contains("prompt=v1");
    }

    @Test
    @DisplayName("录制结果的 confidence 也必须在 [0,1]")
    void recordedConfidenceIsRangeChecked() {
        assertThatThrownBy(() -> new RecordedIntent("A", Intent.EXECUTE, true, null, false, 1.5, false))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[0,1]");
    }

    @Test
    @DisplayName("结果列表不可变")
    void resultsAreImmutable() {
        IntentRunRecording r = new IntentRunRecording(PROV, List.of(R1));
        assertThatThrownBy(() -> r.results().add(R2))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
