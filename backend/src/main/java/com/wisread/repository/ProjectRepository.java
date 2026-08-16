package com.wisread.repository;

import com.wisread.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    List<Project> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<Project> findByUserIdAndId(Long userId, Long id);

    long countByUserId(Long userId);
}
