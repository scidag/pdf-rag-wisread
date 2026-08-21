package com.wisread.repository;

import com.wisread.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long userId);

    List<Project> findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(Long userId);

    Optional<Project> findByUserIdAndIdAndDeletedAtIsNull(Long userId, Long id);

    Optional<Project> findByUserIdAndIdAndDeletedAtIsNotNull(Long userId, Long id);

    long countByUserIdAndDeletedAtIsNull(Long userId);
}
