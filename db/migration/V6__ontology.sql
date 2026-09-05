-- =====================================================================
-- 轻量本体：概念层 + 关系层 + 规则层
--
-- 设计说明见《轻量本体设计.md》与《本体论方法论评估.md》。
--
-- 【为什么是「轻量」】不引入 OWL / 推理机 / SPARQL / SHACL。
-- 三个语义层面的理由（不是成本理由）：
--   ① OWL 的开放世界假设与运维需要的闭世界冲突——
--      影响面分析要的是「CMDB 说这 5 个是下游，所以就是这 5 个」，
--      而 OWA 只能推出「未知」。
--   ② OWL 与 SWRL 都单调、无否定即失败，无法表达
--      「除非标记为核心，否则一人审批」这类默认规则。
--   ③ 形式本体要求领域十年尺度稳定，而服务拓扑每周变。
-- 但仍借用本体工程的两条方法：Competency Questions 界定范围、
-- OntoClean 检验分类。
--
-- 【OntoClean 的一处修正】criticality 是属性而不是子类。
-- 「核心」是非刚性的：服务被提升为核心时它仍然是同一个服务。
-- 非刚性属性不能做子类划分依据，只能是角色。
--
-- 【SKOS 命名对齐】pref_label / alt_labels / hidden_labels / parent_id(broader)
-- 对应 skos:prefLabel / altLabel / hiddenLabel / broader。
-- 采纳命名约定但不采纳 RDF——将来若要走 L2，R2RML 映射是机械的一对一。
--
-- 【为什么标签用一张表而不是 TEXT[]】
-- 热路径是 findByMention：WHERE label = ?（实体链接时按词精确查概念）。
-- GIN 索引优化的是 text[] 的包含运算 @>，不是等值查询；
-- 等值查询在归一化标签表上走 B-tree 更快，且能加唯一约束防重名。
-- 另外 TEXT[] 是 PostgreSQL 专有类型，JdbcOntologyStore 就无法用 H2 验证
-- —— 用假 Connection 测 SQL 等于没测，这一条比索引选型更重要。
--
-- 【关系层可能为空】它依赖 CMDB 的依赖数据，那是已登记的信息缺口。
-- CMDB 没有依赖关系时，本体会退化成受控词表——这不影响概念层的价值。
--
-- 方言：PostgreSQL 10+
-- =====================================================================

CREATE TABLE IF NOT EXISTS onto_concept (
    id             VARCHAR(64)   NOT NULL PRIMARY KEY,
    pref_label     VARCHAR(191)  NOT NULL,
    parent_id      VARCHAR(64)   REFERENCES onto_concept(id),
    kind           VARCHAR(32)   NOT NULL,
    criticality    VARCHAR(16)   NOT NULL DEFAULT 'NORMAL',
    description    VARCHAR(500),
    updated_at     TIMESTAMP     NOT NULL,
    CONSTRAINT chk_onto_criticality CHECK (criticality IN ('CRITICAL','HIGH','NORMAL')),
    CONSTRAINT chk_onto_kind CHECK (kind IN ('ALERT','SERVICE','OPERATION','REMEDIATION','DOMAIN'))
);

COMMENT ON TABLE  onto_concept             IS '轻量本体概念层；范围由 Competency Questions CQ1~CQ8 界定';
COMMENT ON COLUMN onto_concept.pref_label  IS 'skos:prefLabel';
COMMENT ON COLUMN onto_concept.parent_id   IS 'skos:broader（is-a）';
COMMENT ON COLUMN onto_concept.criticality IS '属性而非子类：OntoClean 判定「核心」是非刚性的';

CREATE INDEX IF NOT EXISTS idx_onto_concept_parent ON onto_concept (parent_id);
CREATE INDEX IF NOT EXISTS idx_onto_concept_kind   ON onto_concept (kind);


-- 标签表：吸收 pref / alt / hidden 三类，是实体链接的唯一查询入口
CREATE TABLE IF NOT EXISTS onto_concept_label (
    concept_id  VARCHAR(64)  NOT NULL REFERENCES onto_concept(id) ON DELETE CASCADE,
    label       VARCHAR(191) NOT NULL,
    label_type  VARCHAR(16)  NOT NULL,
    PRIMARY KEY (concept_id, label_type, label),
    CONSTRAINT chk_onto_label_type CHECK (label_type IN ('PREF','ALT','HIDDEN'))
);

COMMENT ON TABLE  onto_concept_label            IS 'skos:prefLabel / altLabel / hiddenLabel 三合一；ALT 吸收了受控词表（别名表）';
COMMENT ON COLUMN onto_concept_label.label_type IS 'PREF 首选 / ALT 同义 / HIDDEN 拼错的、口语的写法';

-- 实体链接的热路径索引。归一化 + 小写化后入库，查询侧同样小写化
CREATE INDEX IF NOT EXISTS idx_onto_label_lookup ON onto_concept_label (label, label_type);


CREATE TABLE IF NOT EXISTS onto_relation (
    subject     VARCHAR(64)  NOT NULL,
    predicate   VARCHAR(64)  NOT NULL,
    object      VARCHAR(64)  NOT NULL,
    source      VARCHAR(64)  NOT NULL,
    updated_at  TIMESTAMP    NOT NULL,
    PRIMARY KEY (subject, predicate, object)
);

COMMENT ON TABLE  onto_relation           IS '带类型的关系；类型是向量相似度给不了的信息';
COMMENT ON COLUMN onto_relation.predicate IS 'depends_on / deployed_on / owned_by / triggers / remediated_by / requires_approval';
COMMENT ON COLUMN onto_relation.source    IS 'CMDB / MANUAL；用于判断可信度，CMDB 同步的会被覆盖，人工的不会';

CREATE INDEX IF NOT EXISTS idx_onto_rel_subj ON onto_relation (subject, predicate);
CREATE INDEX IF NOT EXISTS idx_onto_rel_obj  ON onto_relation (object, predicate);


CREATE TABLE IF NOT EXISTS onto_rule (
    id           VARCHAR(64)  NOT NULL PRIMARY KEY,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    description  VARCHAR(500) NOT NULL,
    hit_count    BIGINT       NOT NULL DEFAULT 0,
    updated_at   TIMESTAMP    NOT NULL
);

COMMENT ON TABLE  onto_rule           IS '规则注册表；规则本体在 Java（OntologyRule 实现类），这里只做开关与统计';
COMMENT ON COLUMN onto_rule.hit_count IS '长期为 0 的规则说明它没用，应该删掉而不是留着';

CREATE INDEX IF NOT EXISTS idx_onto_rule_enabled ON onto_rule (enabled);
