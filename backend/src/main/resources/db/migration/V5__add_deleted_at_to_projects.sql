ALTER TABLE projects ADD COLUMN deleted_at TIMESTAMP;
CREATE INDEX idx_projects_user_deleted_created ON projects(user_id, deleted_at, created_at);
