-- 知识库表：独立于项目的文档容器聚合根（与 projects 平行，全新独立模块）。
-- 现有 documents/project_id 链路保持不动；知识库文档走 knowledge_documents/knowledge_chunks 新表（见 V10）。
-- status 状态机：DRAFT(草稿) / PROCESSING(处理中) / ACTIVE(可用) / ARCHIVED(归档)
CREATE TABLE knowledge_bases (
    id                   BIGSERIAL    PRIMARY KEY,
    user_id              BIGINT       NOT NULL REFERENCES users(id),
    name                 VARCHAR(100) NOT NULL,
    description          TEXT,
    status               VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    top_k                INT          NOT NULL DEFAULT 10,
    similarity_threshold REAL         NOT NULL DEFAULT 0.65,
    rerank_enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    rerank_k             INT          NOT NULL DEFAULT 3,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at           TIMESTAMPTZ
);

CREATE INDEX idx_knowledge_bases_user ON knowledge_bases(user_id, deleted_at);

-- 会话可选绑定知识库（null = 不绑定，问答走原有项目检索；绑定后走知识库检索）
ALTER TABLE conversations ADD COLUMN knowledge_base_id BIGINT REFERENCES knowledge_bases(id);
