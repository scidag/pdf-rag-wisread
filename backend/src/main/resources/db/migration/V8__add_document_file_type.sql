ALTER TABLE documents
    ADD COLUMN file_type VARCHAR(20);

ALTER TABLE document_chunks
    ADD COLUMN location_label VARCHAR(50);
