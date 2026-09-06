package com.oncall.eval;

/**
 * 一次跑批的出处。<b>没有它，录制就是一堆无法解释的数字。</b>
 *
 * <p>这套东西存在的全部理由是"改了 prompt 之后质量变了"能被归因。
 * 而一份只写了 {@code INT-011 -> EXECUTE} 的录制，
 * 无法回答"当时用的是哪一版 prompt、哪个模型、什么配置"——
 * 于是 v1 与 v2 的对比会<b>悄悄连模型和配置一起比</b>，
 * 得出的结论没有任何意义，而且看不出来它没有意义。
 *
 * @param model                模型标识。<b>不是可选的</b>：换个模型对结果的影响
 *                             往往大于换 prompt，混在一起比等于什么都没比
 * @param promptVersion        本次跑批用的 prompt 版本，取自
 *                             {@code QueryUnderstanding.promptVersion()}
 * @param rewriteEnabled       生效的 {@code query.rewrite-enabled}
 * @param rewriteMinConfidence 生效的 {@code query.rewrite-min-confidence}
 * @param recordedAtMillis     录制时刻。<b>由调用方传入</b>——
 *                             本项目已经踩过两次"同一个流程里读两次时钟"的坑，
 *                             规则是谁拥有流程谁负责传时刻
 */
public record RunProvenance(String model, String promptVersion, boolean rewriteEnabled,
                            double rewriteMinConfidence, long recordedAtMillis) {

    public RunProvenance {
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空：换个模型对结果的影响往往大于"
                    + "换 prompt，不记模型就没法解释两次跑批的差异");
        }
        if (promptVersion == null || promptVersion.isBlank()) {
            throw new IllegalArgumentException("promptVersion 不能为空：这是归因的最低要求");
        }
        if (rewriteMinConfidence < 0.0 || rewriteMinConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "rewriteMinConfidence 必须落在 [0,1]，收到 " + rewriteMinConfidence);
        }
    }

    /** 人读的一行摘要，写进报告头。 */
    public String summary() {
        return "model=" + model + " prompt=" + promptVersion
                + " rewrite=" + rewriteEnabled + "/" + rewriteMinConfidence;
    }
}
