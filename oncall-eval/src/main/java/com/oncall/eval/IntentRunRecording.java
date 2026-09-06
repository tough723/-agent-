package com.oncall.eval;

import com.oncall.agent.query.Intent;
import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 一次跑批的完整录制：出处 + 每条用例的输出。
 *
 * <p><b>这就是 L3 两半之间的那个接口。</b>
 * 产出一半（真模型，非确定，不在 CI）写出它；
 * 判定一半（纯计算，确定，在 CI）读它。
 * 门槛因此是确定的，即使被测系统不是。
 */
public record IntentRunRecording(RunProvenance provenance, List<RecordedIntent> results) {

    public IntentRunRecording {
        Objects.requireNonNull(provenance, "provenance");
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException("录制里一条结果都没有——"
                    + "空录制会让所有指标的分母变成 0，必须当成错误");
        }
        results = List.copyOf(results);
    }

    /** 某条用例的录制结果。 */
    public Optional<RecordedIntent> forCase(String caseId) {
        return results.stream().filter(r -> r.caseId().equals(caseId)).findFirst();
    }

    public int size() {
        return results.size();
    }

    // ------------------------------------------------------------------ 序列化

    public String toYaml() {
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> prov = new LinkedHashMap<>();
        prov.put("model", provenance.model());
        prov.put("promptVersion", provenance.promptVersion());
        prov.put("rewriteEnabled", provenance.rewriteEnabled());
        prov.put("rewriteMinConfidence", provenance.rewriteMinConfidence());
        prov.put("recordedAtMillis", provenance.recordedAtMillis());
        root.put("provenance", prov);

        List<Map<String, Object>> rows = new ArrayList<>();
        for (RecordedIntent r : results) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("caseId", r.caseId());
            row.put("intent", r.intent().name());
            row.put("intentFromRule", r.intentFromRule());
            row.put("llmIntent", r.llmIntent() == null ? null : r.llmIntent().name());
            row.put("rewritten", r.rewritten());
            row.put("confidence", r.confidence());
            row.put("degraded", r.degraded());
            rows.add(row);
        }
        root.put("results", rows);
        return new Yaml().dump(root);
    }

    /**
     * 反序列化。
     *
     * <p><b>provenance 缺任何一个字段都直接抛</b>，不给默认值。
     * 给 {@code model} 一个默认值等于允许"不知道是哪个模型跑的"这种录制存在，
     * 而那种录制算出来的指标是无法解释的——比没有指标更糟。
     */
    public static IntentRunRecording fromYaml(String yaml) {
        Object root = new Yaml().load(yaml);
        if (!(root instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("录制的顶层应当是一个映射");
        }
        if (!(m.get("provenance") instanceof Map<?, ?> p)) {
            throw new IllegalArgumentException("录制缺少 provenance："
                    + "没有出处的录制无法归因，不允许存在");
        }
        RunProvenance prov = new RunProvenance(
                require(p, "model"),
                require(p, "promptVersion"),
                Boolean.parseBoolean(require(p, "rewriteEnabled")),
                Double.parseDouble(require(p, "rewriteMinConfidence")),
                Long.parseLong(require(p, "recordedAtMillis")));

        if (!(m.get("results") instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("录制的 results 为空");
        }
        List<RecordedIntent> out = new ArrayList<>();
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> row)) {
                throw new IllegalArgumentException("录制里有一条结果不是映射：" + o);
            }
            String caseId = require(row, "caseId");
            out.add(new RecordedIntent(
                    caseId,
                    parseIntent(caseId, require(row, "intent")),
                    Boolean.parseBoolean(require(row, "intentFromRule")),
                    row.get("llmIntent") == null ? null : parseIntent(caseId, require(row, "llmIntent")),
                    Boolean.parseBoolean(require(row, "rewritten")),
                    Double.parseDouble(require(row, "confidence")),
                    Boolean.parseBoolean(require(row, "degraded"))));
        }
        return new IntentRunRecording(prov, out);
    }

    private static String require(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new IllegalArgumentException("录制缺少字段 \"" + key + "\"。"
                    + "刻意不给默认值：缺了出处的录制算出来的指标无法解释");
        }
        return String.valueOf(v);
    }

    private static Intent parseIntent(String caseId, String raw) {
        return Intent.parse(raw).orElseThrow(() -> new IllegalArgumentException(
                caseId + " 的意图 \"" + raw + "\" 不在闭集内"));
    }
}
