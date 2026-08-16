-- 项目表：项目级对话的归属实体
CREATE TABLE projects (
    id          BIGSERIAL    PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_projects_user_created ON projects(user_id, created_at);

-- documents 增加项目归属（可空，兼容存量数据；新上传强制非空）
ALTER TABLE documents ADD COLUMN project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE;
CREATE INDEX idx_documents_project ON documents(project_id);

-- conversations 从文档级改为项目级：加 project_id，document_id 改可空（保留兼容，不再使用）
ALTER TABLE conversations ADD COLUMN project_id BIGINT REFERENCES projects(id) ON DELETE CASCADE;
ALTER TABLE conversations ALTER COLUMN document_id DROP NOT NULL;
CREATE INDEX idx_conversations_project ON conversations(project_id);

-- answer_sources 冗余 document_id，用于跨文档检索时来源卡显示文档名
ALTER TABLE answer_sources ADD COLUMN document_id BIGINT REFERENCES documents(id) ON DELETE CASCADE;
