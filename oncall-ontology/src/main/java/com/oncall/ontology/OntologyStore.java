package com.oncall.ontology;

import java.util.List;
import java.util.Optional;

/**
 * 本体存储端口。
 *
 * <p>概念层与关系层都走这个端口，但**它们的可用性不同**：
 * 概念层是人工编写的，一定可用；关系层依赖 CMDB 的依赖数据，
 * 而那是一个尚未确认的信息缺口（见 DEVLONG.md 第 9 节）。
 * CMDB 没有依赖关系时关系层为空，本体会退化成受控词表——
 * <b>这不影响概念层的价值</b>，所以两层的实现是分开的。
 */
public interface OntologyStore {

    // ------------------------------------------------------------ 概念层

    void putConcept(OntoConcept concept);

    Optional<OntoConcept> concept(String id);

    List<OntoConcept> allConcepts();

    List<OntoConcept> children(String parentId);

    /** 按标签（pref / alt / hidden）匹配，用于实体链接。 */
    List<OntoConcept> findByMention(String mention);

    // ------------------------------------------------------------ 关系层

    void putRelation(OntoRelation relation);

    /** 某个主体的某类关系的直接对象。 */
    List<String> objects(String subject, String predicate);

    /** 某个客体被哪些主体以该关系指向。用于反向查询（如「谁依赖我」）。 */
    List<String> subjects(String object, String predicate);
}
