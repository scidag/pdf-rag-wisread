package com.wisread.service.impl;

import com.wisread.service.ProjectService;

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

/**
 * 项目（Project）业务服务的实现，负责项目维度的增删改查与软删除管理。
 * 实现要点：所有写操作均为事务方法；项目删除采用“软删除”（写 deleted_at 时间戳）
 * 而非物理删除，以支持回收站与恢复；任何跨用户访问都先经 {@link #findOwnedProject}
 * 校验归属，杜绝越权访问他人项目。countConversations 通过查询后取 size 统计会话数。
 */
@Service
public class ProjectServiceImpl implements ProjectService {

    private final ProjectRepository projectRepository;
    private final DocumentRepository documentRepository;
    private final ConversationRepository conversationRepository;

    public ProjectServiceImpl(
            ProjectRepository projectRepository,
            DocumentRepository documentRepository,
            ConversationRepository conversationRepository
    ) {
        this.projectRepository = projectRepository;
        this.documentRepository = documentRepository;
        this.conversationRepository = conversationRepository;
    }

    /**
     * 创建项目。校验名称非空，绑定所属用户，插入后返回持久化实体。
     */
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

    /**
     * 列出当前用户未删除的项目，按创建时间倒序。
     */
    @Transactional(readOnly = true)
    public List<Project> list(Long userId) {
        return projectRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId);
    }

    /**
     * 列出当前用户已软删除（进入回收站）的项目，按删除时间倒序。
     */
    @Transactional(readOnly = true)
    public List<Project> listDeleted(Long userId) {
        return projectRepository.findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(userId);
    }

    /**
     * 获取单个项目（带归属校验）。
     */
    @Transactional(readOnly = true)
    public Project get(Long userId, Long projectId) {
        return findOwnedProject(userId, projectId);
    }

    /**
     * 更新项目。仅覆盖请求中提供的非空字段（名称/描述），保持其余字段不变。
     */
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

    /**
     * 软删除单个项目：仅记录删除时间，不物理删除，便于后续恢复。
     */
    @Transactional
    public void delete(Long userId, Long projectId) {
        Project project = findOwnedProject(userId, projectId);
        project.setDeletedAt(Instant.now());
        projectRepository.updateById(project);
    }

    /**
     * 批量软删除项目：先去重再逐个删除，避免重复操作同一项目。
     */
    @Transactional
    public void deleteBatch(Long userId, List<Long> projectIds) {
        for (Long projectId : projectIds.stream().distinct().toList()) {
            delete(userId, projectId);
        }
    }

    /**
     * 恢复已软删除的项目：清空 deleted_at 使其重新出现在正常列表。
     * 仅针对已删除状态的项目，否则视为不存在而抛 NOT_FOUND。
     */
    @Transactional
    public Project restore(Long userId, Long projectId) {
        Project project = projectRepository.findByUserIdAndIdAndDeletedAtIsNotNull(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        project.setDeletedAt(null);
        projectRepository.updateById(project);
        return project;
    }

    /**
     * 校验项目归属并返回实体。所有需要操作具体项目的方法都先经此方法，
     * 通过 user_id + id + 未删除 三条件定位，越权或不存在即抛 NOT_FOUND。
     */
    public Project findOwnedProject(Long userId, Long projectId) {
        return projectRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
    }

    /**
     * 统计某项目下的文档数量。
     */
    @Transactional(readOnly = true)
    public long countDocuments(Long projectId) {
        return documentRepository.countByProjectId(projectId);
    }

    /**
     * 统计某用户在某项目下的会话数量（查询后取列表大小）。
     */
    @Transactional(readOnly = true)
    public long countConversations(Long userId, Long projectId) {
        return conversationRepository.findByUserIdAndProjectIdOrderByUpdatedAtDesc(userId, projectId).size();
    }
}
