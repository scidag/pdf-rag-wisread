package com.wisread.service;

import com.wisread.dto.CreateProjectRequest;
import com.wisread.dto.UpdateProjectRequest;
import com.wisread.entity.Document;
import com.wisread.entity.Project;
import com.wisread.exception.ApiException;
import com.wisread.repository.ConversationRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

@Service
public class ProjectService {

    private static final Logger log = LoggerFactory.getLogger(ProjectService.class);

    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;
    private final MinioStorageService minioStorageService;
    private final VectorIndexingService vectorIndexingService;

    public ProjectService(
            ProjectRepository projectRepository,
            DocumentRepository documentRepository,
            ConversationRepository conversationRepository,
            MinioStorageService minioStorageService,
            VectorIndexingService vectorIndexingService
    ) {
        this.projectRepository = projectRepository;
        this.documentRepository = documentRepository;
        this.conversationRepository = conversationRepository;
        this.minioStorageService = minioStorageService;
        this.vectorIndexingService = vectorIndexingService;
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
        projectRepository.save(project);
        return project;
    }

    @Transactional(readOnly = true)
    public List<Project> list(Long userId) {
        return projectRepository.findByUserIdOrderByCreatedAtDesc(userId);
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
        projectRepository.save(project);
        return project;
    }

    @Transactional
    public void delete(Long userId, Long projectId) {
        Project project = findOwnedProject(userId, projectId);
        List<Document> documents = documentRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
        for (Document document : documents) {
            vectorIndexingService.deleteByDocumentId(document.getId());
        }
        conversationRepository.findByUserIdAndProjectIdOrderByUpdatedAtDesc(userId, projectId)
                .forEach(conversationRepository::delete);
        documentRepository.deleteAll(documents);
        projectRepository.delete(project);

        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                documents.stream()
                        .filter(document -> document.getFileKey() != null)
                        .forEach(ProjectService.this::deleteFileBestEffort);
            }
        });
    }

    private void deleteFileBestEffort(Document document) {
        try {
            minioStorageService.deleteObject(document.getFileKey());
        } catch (Exception exception) {
            log.warn("Failed to delete MinIO object after project deletion: {}", document.getFileKey(), exception);
        }
    }

    public Project findOwnedProject(Long userId, Long projectId) {
        return projectRepository.findByUserIdAndId(userId, projectId)
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
