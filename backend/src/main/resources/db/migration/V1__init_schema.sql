CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE,
    email         VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    avatar_url    VARCHAR(500),
    status        SMALLINT     NOT NULL DEFAULT 1,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_sessions (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    refresh_token_hash VARCHAR(255),
    device             VARCHAR(100),
    ip_address         VARCHAR(50),
    expires_at         TIMESTAMP,
    created_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_sessions_user ON user_sessions(user_id);

CREATE TABLE documents (
    id                     BIGSERIAL PRIMARY KEY,
    user_id                BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    filename               VARCHAR(255),
    file_key               VARCHAR(500),
    file_size              BIGINT,
    page_count             INT,
    token_count            INT,
    status                 VARCHAR(20)  NOT NULL DEFAULT 'UPLOADED',
    retry_count            INT          NOT NULL DEFAULT 0,
    error_message          TEXT,
    embedding_model_version VARCHAR(50),
    created_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_documents_user_created ON documents(user_id, created_at);

CREATE TABLE document_chunks (
    id                      BIGSERIAL PRIMARY KEY,
    document_id             BIGINT      NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id                 BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    chunk_index             INT         NOT NULL,
    content                 TEXT        NOT NULL,
    page_start              INT,
    page_end                INT,
    token_count             INT,
    embedding               VECTOR(1024),
    embedding_model_version VARCHAR(50),
    created_at              TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_chunks_document ON document_chunks(document_id);
CREATE INDEX idx_chunks_user_document ON document_chunks(user_id, document_id);
CREATE INDEX idx_chunks_embedding ON document_chunks USING hnsw (embedding vector_cosine_ops);

CREATE TABLE conversations (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    document_id BIGINT       NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    title       VARCHAR(255),
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE messages (
    id              BIGSERIAL PRIMARY KEY,
    conversation_id BIGINT      NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role            VARCHAR(20) NOT NULL,
    content         TEXT        NOT NULL,
    status          VARCHAR(20),
    created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_messages_conversation ON messages(conversation_id, created_at);

CREATE TABLE answer_sources (
    id              BIGSERIAL PRIMARY KEY,
    message_id      BIGINT  NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    chunk_id        BIGINT  NOT NULL REFERENCES document_chunks(id) ON DELETE CASCADE,
    relevance_score FLOAT,
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_answer_sources_message ON answer_sources(message_id);

CREATE TABLE document_jobs (
    id            BIGSERIAL PRIMARY KEY,
    document_id   BIGINT      NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    job_type      VARCHAR(30) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    attempt       INT         NOT NULL DEFAULT 0,
    error_message TEXT,
    started_at    TIMESTAMP,
    finished_at   TIMESTAMP,
    created_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE usage_logs (
    id             BIGSERIAL PRIMARY KEY,
    user_id        BIGINT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    model          VARCHAR(100),
    input_tokens   INT,
    output_tokens  INT,
    cost_estimate  NUMERIC(12,6),
    created_at     TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE feedback (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message_id  BIGINT      NOT NULL REFERENCES messages(id) ON DELETE CASCADE,
    rating      SMALLINT,
    comment     TEXT,
    created_at  TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);
