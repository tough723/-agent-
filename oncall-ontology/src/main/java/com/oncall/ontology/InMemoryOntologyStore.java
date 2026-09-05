package com.oncall.ontology;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存实现。概念层规模在一两百个概念，全量常驻内存没有压力。
 *
 * <p>关系层在多实例部署下必须换成 {@link JdbcOntologyStore}，
 * 理由与配置治理一样：CMDB 同步任务写进 A 实例的内存，B 实例看不到。
 *
 * <p><b>所有返回的列表都按 id 排序</b>。这不是洁癖：
 * 遍历结果会拼进提示词，而 {@code ConcurrentHashMap} 的迭代顺序不稳定，
 * 不排序的话同一个问题两次生成出不同的提示词，答案会无理由地抖动——
 * 这类问题在排查时几乎找不到原因，因为「输入一样」这个前提已经不成立了。
 */
public final class InMemoryOntologyStore implements OntologyStore {

    private final Map<String, OntoConcept> concepts = new ConcurrentHashMap<>();
    private final Map<String, OntoRelation> relations = new ConcurrentHashMap<>();

    @Override
    public void putConcept(OntoConcept concept) {
        concepts.put(concept.id(), concept);
    }

    @Override
    public Optional<OntoConcept> concept(String id) {
        return Optional.ofNullable(concepts.get(id));
    }

    @Override
    public List<OntoConcept> allConcepts() {
        List<OntoConcept> out = new ArrayList<>(concepts.values());
        out.sort(Comparator.comparing(OntoConcept::id));
        return out;
    }

    @Override
    public List<OntoConcept> children(String parentId) {
        List<OntoConcept> out = new ArrayList<>();
        if (parentId == null) {
            return out;
        }
        for (OntoConcept c : concepts.values()) {
            if (parentId.equals(c.parentId())) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing(OntoConcept::id));
        return out;
    }

    @Override
    public List<OntoConcept> findByMention(String mention) {
        List<OntoConcept> out = new ArrayList<>();
        for (OntoConcept c : concepts.values()) {
            if (c.matches(mention)) {
                out.add(c);
            }
        }
        out.sort(Comparator.comparing(OntoConcept::id));
        return out;
    }

    @Override
    public void putRelation(OntoRelation relation) {
        relations.put(key(relation.subject(), relation.predicate(), relation.object()), relation);
    }

    @Override
    public List<String> objects(String subject, String predicate) {
        List<String> out = new ArrayList<>();
        for (OntoRelation r : relations.values()) {
            if (r.subject().equals(subject) && r.predicate().equals(predicate)) {
                out.add(r.object());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    @Override
    public List<String> subjects(String object, String predicate) {
        List<String> out = new ArrayList<>();
        for (OntoRelation r : relations.values()) {
            if (r.object().equals(object) && r.predicate().equals(predicate)) {
                out.add(r.subject());
            }
        }
        out.sort(Comparator.naturalOrder());
        return out;
    }

    private static String key(String s, String p, String o) {
        return s + "\u0000" + p + "\u0000" + o;
    }
}
