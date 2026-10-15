-- 知识库专属文档表：独立于现有 documents（documents/project_id 链路保持不动）。
-- 知识库是全新独立入口，其文档管理使用本表，与项目文档互不影响。
CREATE TABLE knowledge_documents (
    id                      BIGSERIAL    PRIMARY KEY,
    knowledge_base_id       BIGINT       NOT NULL REFERENCES knowledge_bases(id),
    user_id                 BIGINT       NOT NULL,
    filename                VARCHAR(255) NOT NULL,
    file_key                VARCHAR(500) NOT NULL,
    file_size               BIGINT,
    file_type               VARCHAR(20),
    page_count              INT,
    token_count             INT,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',  -- UPLOADED/PROCESSING/INDEXED/FAILED（状态口径与 documents 一致）
    retry_count             INT          NOT NULL DEFAULT 0,
    error_message           TEXT,
    embedding_model_version VARCHAR(50),
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_documents_kb ON knowledge_documents(knowledge_base_id, status);
CREATE INDEX idx_knowledge_documents_user ON knowledge_documents(user_id);

-- 知识库专属切片表：含 pgvector 向量列（1024 维，与 DashScope EmbeddingModel 对齐），
-- 检索时按 user_id + knowledge_base_id + 文档状态过滤。
CREATE TABLE knowledge_chunks (
    id                      BIGSERIAL   PRIMARY KEY,
    document_id             BIGINT      NOT NULL REFERENCES knowledge_documents(id),
    knowledge_base_id       BIGINT      NOT NULL REFERENCES knowledge_bases(id),
    user_id                 BIGINT      NOT NULL,
    chunk_index             INT         NOT NULL,
    content                 TEXT        NOT NULL,
    page_start              INT,
    page_end                INT,
    location_label          VARCHAR(100),
    token_count             INT,
    embedding               VECTOR(1024),
    embedding_model_version VARCHAR(50),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_knowledge_chunks_doc ON knowledge_chunks(document_id, chunk_index);
CREATE INDEX idx_knowledge_chunks_user ON knowledge_chunks(user_id);
CREATE INDEX idx_knowledge_chunks_embedding ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);
