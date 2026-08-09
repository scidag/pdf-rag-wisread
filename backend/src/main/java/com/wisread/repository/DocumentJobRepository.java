package com.wisread.repository;

import com.wisread.entity.DocumentJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DocumentJobRepository extends JpaRepository<DocumentJob, Long> {

    Optional<DocumentJob> findByDocumentId(Long documentId);
}
