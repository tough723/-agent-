package com.oncall.agent.query;

/**
 * 查询改写用到的两个开关。
 *
 * <p><b>为什么要抽这个端口，而不是让 {@link IntentClassifier} 直接读
 * {@code ConfigService}</b>：这两个值的读取时机决定了评测结果可不可信。
 *
 * <p>生产环境要<b>每次调用都读</b>——它们是 {@code RUNTIME_HOT}，
 * 构造时读一次就成了假热更新。用 {@link ConfigBackedQuerySettings}。
 *
 * <p>但评测跑批必须<b>整批冻结</b>：一次跑批如果跨了一次配置变更，
 * 那么录下来的输出就混合了两组配置，
 * 而"改 prompt 之后质量变了"这个结论会同时混进"改配置之后质量变了"。
 * 用 {@link #of}。
 *
 * <p>这不是理论上的洁癖。评测录制里必须写下"当时用的是什么配置"，
 * 而<b>唯一能保证这个记录为真的办法，就是让分类器用的值和记录下来的值
 * 是同一个对象</b>——分别读两次，中间就可能变。
 */
public interface QuerySettings {

    /** 是否启用查询改写。 */
    boolean rewriteEnabled();

    /** 低于此置信度不改写。 */
    double rewriteMinConfidence();

    /** 冻结的一组值，用于评测跑批。 */
    static QuerySettings of(boolean rewriteEnabled, double rewriteMinConfidence) {
        if (rewriteMinConfidence < 0.0 || rewriteMinConfidence > 1.0) {
            throw new IllegalArgumentException(
                    "rewriteMinConfidence 必须落在 [0,1]，收到 " + rewriteMinConfidence);
        }
        return new QuerySettings() {
            @Override
            public boolean rewriteEnabled() {
                return rewriteEnabled;
            }

            @Override
            public double rewriteMinConfidence() {
                return rewriteMinConfidence;
            }

            @Override
            public String toString() {
                return "QuerySettings[rewriteEnabled=" + rewriteEnabled
                        + ", rewriteMinConfidence=" + rewriteMinConfidence + "]";
            }
        };
    }
}
