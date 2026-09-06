package com.oncall.eval;

import com.oncall.agent.query.Intent;

import java.util.Objects;

/**
 * 一条用例的模型输出。
 *
 * <p>字段刻意比"最终意图"多：
 * <ul>
 *   <li>{@code intentFromRule} —— 这个结论是规则层定的还是模型定的。
 *       两者的可信度完全不同，混在一起算准确率会把
 *       "正则永远对"这件事算进模型的成绩里；</li>
 *   <li>{@code llmIntent} —— 模型自报的意图，可能为 {@code null}。
 *       规则层与模型的分歧率是调正则的唯一依据；</li>
 *   <li>{@code degraded} —— 模型输出是否可疑。降级率偏高说明
 *       问题在模型/prompt 而不在分类逻辑，这两件事的修法完全不同。</li>
 * </ul>
 *
 * @param caseId 对应 {@link IntentCase#id()}
 */
public record RecordedIntent(String caseId, Intent intent, boolean intentFromRule,
                             Intent llmIntent, boolean rewritten, double confidence,
                             boolean degraded) {

    public RecordedIntent {
        if (caseId == null || caseId.isBlank()) {
            throw new IllegalArgumentException("caseId 不能为空");
        }
        Objects.requireNonNull(intent, caseId + " 的 intent 不能为 null");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException(caseId + " 的 confidence 必须落在 [0,1]，收到 "
                    + confidence);
        }
    }
}
