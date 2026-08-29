CREATE TABLE IF NOT EXISTS chat_logs (
    id                BIGSERIAL PRIMARY KEY,
    user_id           BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    question          TEXT         NOT NULL,
    model             VARCHAR(100),
    retrieved_content TEXT,
    document_names    TEXT,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_logs_user_created ON chat_logs(user_id, created_at);
