-- =====================================================================
-- 知识库（对应模块 oncall-knowledge，规划中）
--
-- 四条已冻结的约束，每条都有具体理由：
--
-- 1. vector(1024) —— text-embedding-v4 的默认维度。
--    HNSW 上限是 2000 维，选 2048 会直接建不了索引。
--    跨版本向量不可比：换 embedding 模型等于全量重嵌 + 召回基线全部作废。
--
-- 2. vector_cosine_ops —— 必须与 COSINE_DISTANCE 匹配。
--    写错不会报错，会静默退化成全表扫描，表现为「突然变慢」。
--
-- 3. id 不用 uuid_generate_v4() —— chunk ID 必须确定性：
--    UUID.nameUUIDFromBytes(docId | version | headingPath | ordinal)
--    否则重复索引会产生重复切片，且无法做版本安全的 upsert。
--
-- 4. 版本安全的 upsert = 先 delete 再 add。VectorStore 没有 upsert 语义，
--    直接 add 会产生重复。
--
-- 另外：文档版本化而不是覆盖——CitationVerifier 要能回答「当时引用的是哪一版」，
-- 覆盖式更新会让历史回答的引用全部失效。
--
-- 方言：PostgreSQL 10+，需要 pgvector 扩展（支持 HNSW 的版本）
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS vector;


CREATE TABLE IF NOT EXISTS kb_document_version (
    id            VARCHAR(64)   NOT NULL PRIMARY KEY,
    doc_id        VARCHAR(191)  NOT NULL,
    version       INT           NOT NULL,
    title         VARCHAR(500)  NOT NULL,
    content_hash  VARCHAR(64)   NOT NULL,
    source_uri    VARCHAR(500),
    status        VARCHAR(32)   NOT NULL,
    created_at    TIMESTAMP     NOT NULL,
    created_by    VARCHAR(64),
    CONSTRAINT uq_kb_doc_ver UNIQUE (doc_id, version)
);

COMMENT ON TABLE  kb_document_version        IS '知识库文档版本；保留期永久';
COMMENT ON COLUMN kb_document_version.status IS 'DRAFT / ACTIVE / RETIRED';

CREATE INDEX IF NOT EXISTS idx_kb_doc_active ON kb_document_version (doc_id, status);


CREATE TABLE IF NOT EXISTS kb_chunk_vector (
    id            VARCHAR(64)   NOT NULL PRIMARY KEY,
    doc_id        VARCHAR(191)  NOT NULL,
    doc_version   INT           NOT NULL,
    ordinal       INT           NOT NULL,
    heading_path  VARCHAR(500),
    parent_id     VARCHAR(64),
    concept_id    VARCHAR(64),
    content       TEXT          NOT NULL,
    token_count   INT           NOT NULL,
    metadata      JSONB,
    embedding     vector(1024)  NOT NULL,
    created_at    TIMESTAMP     NOT NULL
);

COMMENT ON TABLE  kb_chunk_vector              IS '切片向量；维度写死为 1024，改维度要重建表并全量重嵌';
COMMENT ON COLUMN kb_chunk_vector.id           IS '确定性 ID：nameUUIDFromBytes(docId|version|headingPath|ordinal)';
COMMENT ON COLUMN kb_chunk_vector.parent_id    IS '父子块：子块用于精确匹配，父块给模型；父块数量 ≤4 且有 token 上限';
COMMENT ON COLUMN kb_chunk_vector.concept_id   IS '关联轻量本体概念，用于概念过滤检索（见 V6）';

-- HNSW 索引：注意 vector_cosine_ops 必须与检索时的距离类型一致，
-- 不一致不报错，只是静默变成全表扫描
CREATE INDEX IF NOT EXISTS idx_kb_chunk_hnsw
    ON kb_chunk_vector USING HNSW (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_kb_chunk_doc    ON kb_chunk_vector ((metadata->>'doc_id'));
CREATE INDEX IF NOT EXISTS idx_kb_chunk_parent ON kb_chunk_vector (parent_id);
CREATE INDEX IF NOT EXISTS idx_kb_chunk_concept ON kb_chunk_vector (concept_id);
