CREATE TABLE knowledge_document (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    title varchar(200) NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('POLICY', 'GUIDE', 'FAQ')),
    product_id uuid REFERENCES product(id),
    policy_rule_id uuid REFERENCES policy_rule(id),
    sha256 varchar(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    storage_key varchar(500) NOT NULL,
    content_type varchar(100) NOT NULL,
    status varchar(16) NOT NULL DEFAULT 'PROCESSING'
        CHECK (status IN ('PROCESSING', 'READY', 'FAILED', 'DELETED')),
    active_generation integer CHECK (active_generation > 0),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (kind <> 'POLICY' OR policy_rule_id IS NOT NULL),
    CHECK (kind <> 'GUIDE' OR product_id IS NOT NULL),
    CHECK (status <> 'READY' OR active_generation IS NOT NULL),
    CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL))
);
CREATE INDEX ix_document_status_created ON knowledge_document(status, created_at DESC, id);
CREATE INDEX ix_document_product ON knowledge_document(product_id);
CREATE INDEX ix_document_policy ON knowledge_document(policy_rule_id);
COMMENT ON TABLE knowledge_document IS '知识文档与当前发布代次；检索必须同时过滤 READY 和 active_generation';

CREATE TABLE document_ingestion (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL REFERENCES knowledge_document(id),
    generation integer NOT NULL CHECK (generation > 0),
    status varchar(16) NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    stage varchar(16) NOT NULL DEFAULT 'VALIDATING'
        CHECK (stage IN ('VALIDATING', 'PARSING', 'CHUNKING', 'EMBEDDING', 'PUBLISHING', 'DONE')),
    processed_chunks integer NOT NULL DEFAULT 0 CHECK (processed_chunks >= 0),
    total_chunks integer NOT NULL DEFAULT 0 CHECK (total_chunks >= processed_chunks),
    embedding_model varchar(128) NOT NULL,
    embedding_dimensions integer NOT NULL CHECK (embedding_dimensions > 0),
    index_namespace varchar(128) NOT NULL CHECK (length(btrim(index_namespace)) > 0),
    error_code varchar(64),
    attempt integer NOT NULL DEFAULT 0 CHECK (attempt >= 0),
    lease_until timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, generation),
    UNIQUE (document_id, generation, embedding_model, embedding_dimensions, index_namespace),
    CHECK (status <> 'SUCCEEDED' OR (stage = 'DONE' AND processed_chunks = total_chunks AND total_chunks > 0))
);
CREATE UNIQUE INDEX uq_active_ingestion_document ON document_ingestion(document_id)
    WHERE status IN ('QUEUED', 'RUNNING');
CREATE INDEX ix_ingestion_queue_lease ON document_ingestion(status, lease_until, created_at);

-- 发布指针只允许引用已存在的同文档代次；成功状态与切换指针由同一业务事务保证。
ALTER TABLE knowledge_document ADD CONSTRAINT fk_document_active_generation
    FOREIGN KEY (id, active_generation) REFERENCES document_ingestion(document_id, generation);

CREATE TABLE knowledge_chunk (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    document_id uuid NOT NULL,
    generation integer NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal >= 0),
    title_path varchar(500) NOT NULL DEFAULT '',
    page_no integer CHECK (page_no > 0),
    char_start integer NOT NULL CHECK (char_start >= 0),
    char_end integer NOT NULL,
    content text NOT NULL CHECK (length(content) > 0),
    content_hash varchar(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    embedding vector NOT NULL,
    embedding_model varchar(128) NOT NULL,
    embedding_dimensions integer NOT NULL CHECK (embedding_dimensions > 0),
    index_namespace varchar(128) NOT NULL,
    metadata jsonb NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(metadata) = 'object'),
    UNIQUE (document_id, generation, ordinal),
    FOREIGN KEY (document_id, generation, embedding_model, embedding_dimensions, index_namespace)
        REFERENCES document_ingestion(document_id, generation, embedding_model, embedding_dimensions, index_namespace),
    CHECK (char_end > char_start),
    CHECK (vector_dims(embedding) = embedding_dimensions)
);
CREATE INDEX ix_chunk_namespace_document ON knowledge_chunk(index_namespace, document_id, generation);
-- 已知 mock 固定 16 维，使用带维度转换的部分索引。真实模型选定 D 后新增独立命名空间及 vector(D) 索引。
CREATE INDEX ix_chunk_mock_cosine ON knowledge_chunk USING hnsw ((embedding::vector(16)) vector_cosine_ops)
    WHERE index_namespace = 'mock-v1' AND embedding_dimensions = 16;
COMMENT ON TABLE document_ingestion IS '文档导入任务与向量模型快照；发布新代次时保留旧代次以支持失败恢复';
COMMENT ON TABLE knowledge_chunk IS '分块与向量；检索严禁跨模型/维度/命名空间混算；mock 向量不代表语义能力';
COMMENT ON COLUMN knowledge_chunk.char_end IS '原文字符区间右开端点；应用统一字符计数规则';
