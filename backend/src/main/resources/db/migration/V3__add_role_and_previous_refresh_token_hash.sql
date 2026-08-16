-- 用户角色字段（RBAC）
ALTER TABLE users ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'USER';

-- 上一代 refresh token 哈希（用于重用检测）
ALTER TABLE user_sessions ADD COLUMN previous_refresh_token_hash VARCHAR(255);
