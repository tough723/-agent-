package com.oncall.agent.llm;

/**
 * 从模型输出里取出 JSON 正文。
 *
 * <h2>为什么这个类存在：一次「差点复制粘贴」</h2>
 * <p>这段逻辑原本私有在 {@code IntentClassifier} 里。D3-b 写 {@code Planner} 时
 * 同样需要它，而两个类不同包（{@code agent.query} 与 {@code agent.plan}），
 * 包级私有调不到。
 *
 * <p>最省事的做法是复制一份。<b>但同一条解析规则写在两处必然分叉</b>——
 * 将来发现模型新的一种包裹方式，只会修其中一处，
 * 于是「意图分类能解析、计划解析不能」这种偏差会安静地存在很久。
 * 所以抽出来共享，原处改为委托。
 *
 * <h2>为什么需要它</h2>
 * <p>即使 prompt 明确写了「只输出 JSON」，模型仍常常加一层
 * <code>```json</code> 围栏，或者加一句「好的，以下是结果：」。
 * 直接 {@code readTree} 会整段失败——于是一次本来可用的回答被拒了，
 * 而日志里只写「无法解析」。
 *
 * <p><b>这是个启发式，不是解析器。</b>它会在「输出里有多段 JSON」时取错，
 * 但那种输出本身就是坏的，取哪一段都不对。
 */
public final class ModelOutputJson {

    private ModelOutputJson() {
    }

    /** 剥掉代码围栏，再取第一个 <code>{</code> 到最后一个 <code>}</code>。 */
    public static String extract(String raw) {
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstBreak = text.indexOf('\n');
            if (firstBreak > 0) {
                text = text.substring(firstBreak + 1);
            }
            int fence = text.lastIndexOf("```");
            if (fence >= 0) {
                text = text.substring(0, fence);
            }
        }
        int open = text.indexOf('{');
        int close = text.lastIndexOf('}');
        if (open < 0 || close <= open) {
            return text;
        }
        return text.substring(open, close + 1);
    }
}
