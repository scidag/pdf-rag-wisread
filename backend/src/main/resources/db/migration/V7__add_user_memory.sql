-- L2 长期用户记忆表：跨会话、跨项目的用户画像/偏好/事实记忆
-- 设计要点见 docs/memory/memory-system-design.md 第四章：
--   user_id BIGINT 外键对齐 users.id；VECTOR(1024) 对齐 DashScope Embedding 维度；
--   status 三态支持"替代/删除"而不物理清除；HNSW 部分索引只覆盖 ACTIVE 记忆
CREATE TABLE user_memory (
    id                      BIGSERIAL PRIMARY KEY,
    user_id                 BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    content                 TEXT        NOT NULL,
    category                VARCHAR(30) NOT NULL DEFAULT 'FACT',
    importance              SMALLINT    NOT NULL DEFAULT 3,
    status                  VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    source_conversation_id  BIGINT      REFERENCES conversations(id) ON DELETE SET NULL,
    embedding               VECTOR(1024),
    embedding_model_version VARCHAR(50),
    created_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

COMMENT ON TABLE  user_memory                    IS '长期用户记忆（跨会话）';
COMMENT ON COLUMN user_memory.category           IS 'FACT=事实偏好 / GOAL=目标任务 / CONTEXT=背景约束';
COMMENT ON COLUMN user_memory.importance         IS '重要性 1-5，注入排序与淘汰依据';
COMMENT ON COLUMN user_memory.status             IS 'ACTIVE=生效 / SUPERSEDED=被新记忆替代 / DELETED=删除（用户删或超限淘汰）';
COMMENT ON COLUMN user_memory.source_conversation_id IS '记忆来源会话，仅溯源用，会话删除后置空';

-- 活跃记忆才有检索价值，部分索引同时降低写入维护成本
CREATE INDEX idx_user_memory_user_active
    ON user_memory(user_id) WHERE status = 'ACTIVE';
CREATE INDEX idx_user_memory_embedding
    ON user_memory USING hnsw (embedding vector_cosine_ops) WHERE status = 'ACTIVE';
