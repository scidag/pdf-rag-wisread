package com.wisread.service;

import com.wisread.dto.CreateProjectRequest;
import com.wisread.dto.UpdateProjectRequest;
import com.wisread.entity.Project;
import com.wisread.exception.ApiException;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;

    public ProjectService(
            ProjectRepository projectRepository,
            DocumentRepository documentRepository,
            ConversationRepository conversationRepository
    ) {
        this.projectRepository = projectRepository;
        this.documentRepository = documentRepository;
        this.conversationRepository = conversationRepository;
    }

    @Transactional
    public Project create(Long userId, CreateProjectRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "project name is required");
        }
        Project project = new Project();
        project.setUserId(userId);
        project.setName(request.name().trim());
        project.setDescription(request.description());
        projectRepository.insert(project);
        return project;
    }

    @Transactional(readOnly = true)
    public List<Project> list(Long userId) {
        return projectRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public List<Project> listDeleted(Long userId) {
        return projectRepository.findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(userId);
    }

    @Transactional(readOnly = true)
    public Project get(Long userId, Long projectId) {
        return findOwnedProject(userId, projectId);
    }

    @Transactional
    public Project update(Long userId, Long projectId, UpdateProjectRequest request) {
        Project project = findOwnedProject(userId, projectId);
        if (request.name() != null && !request.name().isBlank()) {
            project.setName(request.name().trim());
        }
        if (request.description() != null) {
            project.setDescription(request.description());
        }
        projectRepository.updateById(project);
        return project;
    }

    @Transactional
    public void delete(Long userId, Long projectId) {
        Project project = findOwnedProject(userId, projectId);
        project.setDeletedAt(Instant.now());
        projectRepository.updateById(project);
    }

    @Transactional
    public void deleteBatch(Long userId, List<Long> projectIds) {
        for (Long projectId : projectIds.stream().distinct().toList()) {
            delete(userId, projectId);
        }
    }

    @Transactional
    public Project restore(Long userId, Long projectId) {
        Project project = projectRepository.findByUserIdAndIdAndDeletedAtIsNotNull(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        project.setDeletedAt(null);
        projectRepository.updateById(project);
        return project;
    }

    public Project findOwnedProject(Long userId, Long projectId) {
        return projectRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
    }

    @Transactional(readOnly = true)
    public long countDocuments(Long projectId) {
        return documentRepository.countByProjectId(projectId);
    }

    @Transactional(readOnly = true)
    public long countConversations(Long userId, Long projectId) {
        return conversationRepository.findByUserIdAndProjectIdOrderByUpdatedAtDesc(userId, projectId).size();
    }
}
