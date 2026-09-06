package com.oncall.agent.query;

import java.util.Objects;

/**
 * 一次指代消解的结果。
 *
 * <p>{@code resolvedEntities} <b>必须回显到 UI</b>
 * （"我理解你问的是 payment-api"）——这是《查询理解与知识表示设计》§1.3
 * 四条护栏里的第 1 条，也是成本最低的防错手段：让用户能一眼发现并纠正。
 *
 * @param text       原句里被消解掉的那段文字，例如"这个服务"。
 *                   <b>必须是原句的子串</b>，{@link IntentClassifier} 会校验这一点。
 * @param resolvedTo 消解成的实体名
 * @param source     这个指代是在哪一轮被确定的，例如 {@code turn-2}
 */
public record ResolvedEntity(String text, String resolvedTo, String source) {

    public ResolvedEntity {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(resolvedTo, "resolvedTo");
        // source 允许为 null：模型经常给不出轮次，
        // 而"给不出来源"不该让整条消解结果作废。
    }
}
