package com.oncall.ontology;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 一个本体概念。
 *
 * <p>字段命名对齐 SKOS：{@code prefLabel} = {@code skos:prefLabel}，
 * {@code altLabels} = {@code skos:altLabel}，{@code hiddenLabels} = {@code skos:hiddenLabel}，
 * {@code parentId} = {@code skos:broader}。
 *
 * <p><b>为什么采纳 SKOS 命名但不采纳 RDF</b>：自造名字（比如 {@code aliases}）
 * 将来若要迁移到语义网栈，要重新做一遍语义对照；对齐命名后 R2RML 映射是机械的一对一。
 * 成本只是几个字段名。
 *
 * @param id           概念标识
 * @param prefLabel    首选标签
 * @param altLabels    同义标签——**吸收了受控词表（别名表）**，不是两套并存
 * @param hiddenLabels 隐藏标签：拼错的、口语的写法。用户会打错，检索不能因此失败
 * @param parentId     上位概念（is-a）；顶层为 {@code null}
 * @param kind         概念种类
 * @param criticality  关键程度。<b>属性而非子类</b>，理由见 {@link Criticality}
 */
public record OntoConcept(
        String id,
        String prefLabel,
        List<String> altLabels,
        List<String> hiddenLabels,
        String parentId,
        ConceptKind kind,
        Criticality criticality,
        String description
) {

    public OntoConcept {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(prefLabel, "prefLabel");
        Objects.requireNonNull(kind, "kind");
        altLabels = altLabels == null ? List.of() : List.copyOf(altLabels);
        hiddenLabels = hiddenLabels == null ? List.of() : List.copyOf(hiddenLabels);
        criticality = criticality == null ? Criticality.NORMAL : criticality;
    }

    /**
     * 该概念是否匹配用户输入的这个词。
     *
     * <p>三类标签都参与匹配，且**大小写不敏感**——用户不会按你的规范大小写输入。
     */
    public boolean matches(String mention) {
        if (mention == null || mention.isBlank()) {
            return false;
        }
        String m = mention.trim().toLowerCase(Locale.ROOT);
        if (prefLabel.toLowerCase(Locale.ROOT).equals(m)) {
            return true;
        }
        return altLabels.stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).equals(m))
                || hiddenLabels.stream().anyMatch(a -> a.toLowerCase(Locale.ROOT).equals(m));
    }

    public boolean isRoot() {
        return parentId == null;
    }
}
