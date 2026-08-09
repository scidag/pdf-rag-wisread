package com.wisread.repository;

import com.wisread.entity.DocumentChunk;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentChunkRepository extends JpaRepository<DocumentChunk, Long> {

    void deleteByDocumentId(Long documentId);

    long countByDocumentId(Long documentId);
}
