ALTER TABLE knowledge_bases ADD COLUMN agent_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE knowledge_bases ADD COLUMN max_iterations INT;
