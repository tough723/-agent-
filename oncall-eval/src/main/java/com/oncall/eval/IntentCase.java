package com.oncall.eval;

import com.oncall.agent.query.Intent;

import java.util.Objects;

/**
 * 一条意图标注。
 *
 * <p><b>{@code expect} 的判定标准是"这句话想让系统做什么"，
 * 不是"这句话里出现了什么词"。</b>后者正是正则的能力边界，
 * 用词来标注会让评测退化成对正则的复读——
 * 那样召回率永远是 1.0，而它对真实用户说的话一无所知。
 *
 * @param id     全局唯一，形如 {@code INT-011}。漏判报告要能指着它说"这一条"
 * @param group  分组。{@code execute-paraphrase} 与 {@code false-positive-probe}
 *               这两组承载了本集最重要的信息，见 {@code intent-v1.yaml} 的头注释
 * @param expect 人工标注的意图
 * @param text   用户原话
 */
public record IntentCase(String id, String group, Intent expect, String text) {

    /** 漏判专项：语义上是操作请求，但刻意避开正则里的动词。 */
    public static final String GROUP_EXECUTE_PARAPHRASE = "execute-paraphrase";
    /** 过度命中探针：会被正则命中但语义上不是操作请求。不计入门槛，只用来度量打扰率。 */
    public static final String GROUP_FALSE_POSITIVE_PROBE = "false-positive-probe";

    public IntentCase {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("标注用例缺少 id：漏判报告要能指着某一条说话");
        }
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException(id + " 缺少 group");
        }
        Objects.requireNonNull(expect, id + " 缺少 expect");
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(id + " 缺少 text");
        }
    }
}
