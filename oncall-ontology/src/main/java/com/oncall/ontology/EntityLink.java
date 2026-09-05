package com.oncall.ontology;

import java.util.List;

/**
 * 实体链接的结果。
 *
 * <p><b>多义时返回候选而不是猜</b>，这是本体带来的最重要的一项查询理解改善。
 * 一个会问「你说的是支付网关还是支付批处理？」的系统，
 * 比一个猜一个然后自信作答的系统**安全得多**——
 * 而「知道自己不知道」这件事，别名表给不了，只有概念模型能给。
 *
 * @param status     链接结果
 * @param resolved   唯一命中时的概念；其余情况为 {@code null}
 * @param candidates 多义时的候选，用于向用户澄清
 */
public record EntityLink(Status status, OntoConcept resolved, List<OntoConcept> candidates) {

    public enum Status {
        /** 唯一命中。 */
        RESOLVED,
        /** 多义：必须向用户澄清，不能猜。 */
        AMBIGUOUS,
        /** 没有命中：应降级为纯文本检索，不要硬套概念。 */
        UNRESOLVED
    }

    public EntityLink {
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
    }

    public static EntityLink resolved(OntoConcept concept) {
        return new EntityLink(Status.RESOLVED, concept, List.of());
    }

    public static EntityLink ambiguous(List<OntoConcept> candidates) {
        return new EntityLink(Status.AMBIGUOUS, null, candidates);
    }

    public static EntityLink unresolved() {
        return new EntityLink(Status.UNRESOLVED, null, List.of());
    }

    public boolean isResolved() {
        return status == Status.RESOLVED;
    }

    public boolean needsClarification() {
        return status == Status.AMBIGUOUS;
    }

    /** 给用户的澄清问题文本。 */
    public String clarificationPrompt(String mention) {
        StringBuilder sb = new StringBuilder("你说的「").append(mention).append("」是指");
        for (int i = 0; i < candidates.size(); i++) {
            if (i > 0) {
                sb.append(i == candidates.size() - 1 ? " 还是 " : "、");
            }
            sb.append(candidates.get(i).prefLabel());
        }
        return sb.append("？").toString();
    }
}
