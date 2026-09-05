package com.oncall.ontology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 本体的查询门面：有界遍历 + 实体链接。
 *
 * <p><b>所有遍历都有硬上限</b>，这是刻意的：
 * <ul>
 *   <li>is-a 层级最多 {@value #MAX_ISA_DEPTH} 层——概念层级不该更深，
 *       更深说明建模有问题而不是遍历不够；</li>
 *   <li>关系遍历默认 3 跳——**无界的影响面分析在运维上没有意义**，
 *       「这个服务挂了会影响整个公司」不是可执行的答案。</li>
 * </ul>
 *
 * <p><b>有界传递闭包不需要推理机</b>：SPARQL 里是 property path
 * {@code :depends_on{1,3}}，SQL 里是递归 CTE。
 * 只有**无界**闭包才需要 DL 推理，而那正是我们不需要的能力。
 */
public final class Ontology {

    /** is-a 层级的硬上限。 */
    public static final int MAX_ISA_DEPTH = 8;

    /** 关系遍历的默认跳数上限。 */
    public static final int MAX_HOPS = 3;

    private final OntologyStore store;

    public Ontology(OntologyStore store) {
        this.store = store;
    }

    public OntologyStore store() {
        return store;
    }

    // ------------------------------------------------------------ 实体链接

    /**
     * 把用户提到的词链接到概念。
     *
     * <p>多义时返回 {@link EntityLink.Status#AMBIGUOUS} 与候选，
     * <b>绝不猜</b>——猜错了用户看不出来，答案看起来合理但是错的。
     */
    public EntityLink link(String mention) {
        List<OntoConcept> hits = store.findByMention(mention);
        if (hits.isEmpty()) {
            return EntityLink.unresolved();
        }
        if (hits.size() == 1) {
            return EntityLink.resolved(hits.get(0));
        }
        // 首选标签精确命中的优先：用户说「支付服务」时，
        // prefLabel 就叫「支付服务」的那个概念比别名里带「支付」的更可能是本意
        List<OntoConcept> exact = hits.stream()
                .filter(c -> c.prefLabel().equalsIgnoreCase(mention.trim()))
                .toList();
        if (exact.size() == 1) {
            return EntityLink.resolved(exact.get(0));
        }
        return EntityLink.ambiguous(hits);
    }

    // ------------------------------------------------------------ is-a 遍历

    /** {@code conceptId} 是否是 {@code ancestorId} 的子概念（含自身）。 */
    public boolean isA(String conceptId, String ancestorId) {
        if (conceptId == null || ancestorId == null) {
            return false;
        }
        if (conceptId.equals(ancestorId)) {
            return true;
        }
        Set<String> seen = new HashSet<>();
        String cursor = conceptId;
        for (int depth = 0; depth < MAX_ISA_DEPTH; depth++) {
            if (!seen.add(cursor)) {
                return false;   // 环：数据有问题，当作不成立
            }
            Optional<OntoConcept> c = store.concept(cursor);
            if (c.isEmpty() || c.get().parentId() == null) {
                return false;
            }
            if (ancestorId.equals(c.get().parentId())) {
                return true;
            }
            cursor = c.get().parentId();
        }
        return false;
    }

    /** 自身 + 全部祖先，从近到远。 */
    public List<String> ancestors(String conceptId) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        String cursor = conceptId;
        for (int depth = 0; depth < MAX_ISA_DEPTH && cursor != null; depth++) {
            if (!seen.add(cursor)) {
                break;
            }
            out.add(cursor);
            cursor = store.concept(cursor).map(OntoConcept::parentId).orElse(null);
        }
        return out;
    }

    /**
     * 自身 + 全部后代（广度优先）。
     *
     * <p>这是 CQ1「支付相关的所有服务」的解法：链接到业务域节点后向下展开。
     * <b>别名表只能一对一，域能一对多。</b>
     */
    public List<String> descendants(String conceptId) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(conceptId);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            if (!seen.add(current)) {
                continue;
            }
            out.add(current);
            for (OntoConcept child : store.children(current)) {
                queue.add(child.id());
            }
        }
        return out;
    }

    // ------------------------------------------------------------ 关系遍历

    /**
     * 有界关系遍历。默认最多 {@value #MAX_HOPS} 跳。
     *
     * @param hops 跳数上限，会被夹到 {@code [1, MAX_HOPS]}。
     *             <b>上界必须存在</b>：调用方不能通过传大数把上限绕开。
     * @return 按跳数分层的对象列表，{@code result.get(0)} 是 1 跳可达
     */
    public List<Set<String>> traverse(String subject, String predicate, int hops) {
        int limit = Math.max(1, Math.min(hops, MAX_HOPS));
        List<Set<String>> byHop = new ArrayList<>(limit);
        Set<String> seen = new HashSet<>();
        seen.add(subject);
        Set<String> frontier = new LinkedHashSet<>();
        frontier.add(subject);
        for (int hop = 0; hop < limit; hop++) {
            Set<String> next = new LinkedHashSet<>();
            for (String s : frontier) {
                for (String o : store.objects(s, predicate)) {
                    if (seen.add(o)) {
                        next.add(o);
                    }
                }
            }
            byHop.add(next);
            frontier = next;
            if (frontier.isEmpty()) {
                break;
            }
        }
        return byHop;
    }

    /**
     * 影响面：某服务挂了会影响哪些下游。
     *
     * <p><b>这是闭世界语义</b>：CMDB 说这几个是下游，影响面就限定这几个。
     * OWL 的开放世界假设给不出这种答案——它只能说「未知」，
     * 而凌晨三点「未知」是零价值。这是不引入推理机的核心理由之一。
     */
    public Set<String> impactScope(String serviceId) {
        Set<String> out = new LinkedHashSet<>();
        for (Set<String> hop : traverse(serviceId, OntoRelation.DEPENDS_ON, MAX_HOPS)) {
            out.addAll(hop);
        }
        return out;
    }

    // ------------------------------------------------------------ 属性查询

    /** 某服务的关键程度。查不到时返回 {@link Criticality#NORMAL}——按最不严格处理。 */
    public Criticality criticalityOf(String conceptId) {
        return store.concept(conceptId)
                .map(OntoConcept::criticality)
                .orElse(Criticality.NORMAL);
    }

    /**
     * Runbook 是否可能过期。
     *
     * <p>这不是本体问题（只是一个时间戳判断），但它作为规则 R4 存在，
     * 因为**过期的知识比没有更危险**——系统会自信地引用一份两年前的手册。
     * 对策不是不做知识库，是让系统主动声明「这份可能过期」。
     */
    public static boolean isRunbookStale(long lastUpdatedMillis, long nowMillis, long staleAfterMillis) {
        return nowMillis - lastUpdatedMillis > staleAfterMillis;
    }
}
