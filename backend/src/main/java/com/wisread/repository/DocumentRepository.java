package com.wisread.repository;

import com.wisread.entity.Document;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DocumentRepository extends JpaRepository<Document, Long> {

    Optional<Document> findByUserIdAndId(Long userId, Long id);

    List<Document> findByUserIdOrderByCreatedAtDesc(Long userId);

    List<Document> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    Optional<Document> findByUserIdAndProjectIdAndId(Long userId, Long projectId, Long id);

    long countByUserId(Long userId);

    long countByProjectId(Long projectId);

    long countByUserIdAndProjectId(Long userId, Long projectId);
}
