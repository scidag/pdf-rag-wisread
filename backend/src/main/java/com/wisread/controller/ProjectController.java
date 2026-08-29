package com.wisread.controller;

import com.wisread.dto.BatchDeleteRequest;
import com.wisread.dto.CreateProjectRequest;
import com.wisread.dto.ProjectResponse;
import com.wisread.dto.UpdateProjectRequest;
import com.wisread.entity.Project;
import com.wisread.service.ProjectService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 项目（Project）控制器。
 * 负责知识库项目（项目是文档与对话的容器）相关的 REST 端点：
 * 创建项目、列出项目、查看回收站、查看项目详情、更新项目、恢复项目、删除项目、批量删除项目。
 * 所有接口均需登录（从 Authentication 获取当前用户 ID）。
 * 基础路径：/api/v1/projects
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    /** POST /api/v1/projects：创建知识库项目。
     * 入参：@Valid CreateProjectRequest（项目名称、描述），当前登录用户由 Authentication 解析得到。
     * 业务含义：为当前用户新建一个项目（文档与对话的容器）。
     * 返回：201 Created 及项目信息（含文档数/会话数统计）。 */
    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @Valid @RequestBody CreateProjectRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        Project project = projectService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(userId, project));
    }

    /** GET /api/v1/projects：列出当前用户的项目列表（不含回收站）。
     * 入参：无查询参数，当前登录用户由 Authentication 解析得到。
     * 业务含义：返回该用户所有未删除的项目。
     * 返回：200 OK 及项目信息列表（List<ProjectResponse>）。 */
    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        List<Project> projects = projectService.list(userId);
        Map<Long, long[]> counts = projectService.countBatch(
                userId,
                projects.stream().map(Project::getId).toList()
        );
        return ResponseEntity.ok(projects.stream()
                .map(p -> toResponse(userId, p, counts.get(p.getId())))
                .toList());
    }

    /** GET /api/v1/projects/deleted：列出当前用户的回收站项目。
     * 入参：无查询参数，当前登录用户由 Authentication 解析得到。
     * 业务含义：返回该用户已软删除（可恢复）的项目列表。
     * 返回：200 OK 及已删除项目信息列表（List<ProjectResponse>）。 */
    @GetMapping("/deleted")
    public ResponseEntity<List<ProjectResponse>> listDeleted(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        List<Project> projects = projectService.listDeleted(userId);
        Map<Long, long[]> counts = projectService.countBatch(
                userId,
                projects.stream().map(Project::getId).toList()
        );
        return ResponseEntity.ok(projects.stream()
                .map(p -> toResponse(userId, p, counts.get(p.getId())))
                .toList());
    }

    /** GET /api/v1/projects/{projectId}：获取单个项目详情。
     * 入参：路径变量 projectId（项目 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：返回指定项目的完整信息（含文档数/会话数统计）。
     * 返回：200 OK 及项目信息（ProjectResponse）。 */
    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> get(
            @PathVariable Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        Project project = projectService.get(userId, projectId);
        return ResponseEntity.ok(toResponse(userId, project));
    }

    /** PATCH /api/v1/projects/{projectId}：更新项目信息。
     * 入参：路径变量 projectId（项目 ID），@Valid UpdateProjectRequest（新名称/描述），当前登录用户由 Authentication 解析得到。
     * 业务含义：修改指定项目的基本信息。
     * 返回：200 OK 及更新后的项目信息（ProjectResponse）。 */
    @PatchMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> update(
            @PathVariable Long projectId,
            @Valid @RequestBody UpdateProjectRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        Project project = projectService.update(userId, projectId, request);
        return ResponseEntity.ok(toResponse(userId, project));
    }

    /** PATCH /api/v1/projects/{projectId}/restore：恢复回收站中的项目。
     * 入参：路径变量 projectId（项目 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：将已软删除的项目恢复为正常状态。
     * 返回：200 OK 及恢复后的项目信息（ProjectResponse）。 */
    @PatchMapping("/{projectId}/restore")
    public ResponseEntity<ProjectResponse> restore(
            @PathVariable Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        Project project = projectService.restore(userId, projectId);
        return ResponseEntity.ok(toResponse(userId, project));
    }

    /** DELETE /api/v1/projects/{projectId}：删除项目（软删除）。
     * 入参：路径变量 projectId（项目 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：将指定项目移入回收站（软删除），后续可恢复。
     * 返回：204 No Content。 */
    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        projectService.delete(userId, projectId);
        return ResponseEntity.noContent().build();
    }

    /** POST /api/v1/projects/batch-delete：批量删除项目。
     * 入参：@Valid BatchDeleteRequest（待删除项目 ID 列表，最多 100 个），当前登录用户由 Authentication 解析得到。
     * 业务含义：一次性将多个项目移入回收站（软删除）。
     * 返回：204 No Content。 */
    @PostMapping("/batch-delete")
    public ResponseEntity<Void> deleteBatch(
            @Valid @RequestBody BatchDeleteRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        projectService.deleteBatch(userId, request.ids());
        return ResponseEntity.noContent().build();
    }

    // 将 Project 实体转换为带统计信息（文档数/会话数）的 ProjectResponse DTO
    private ProjectResponse toResponse(Long userId, Project project) {
        long docCount = projectService.countDocuments(project.getId());
        long convCount = projectService.countConversations(userId, project.getId());
        return toResponse(userId, project, new long[]{docCount, convCount});
    }

    private ProjectResponse toResponse(Long userId, Project project, long[] counts) {
        long docCount = counts != null ? counts[0] : 0;
        long convCount = counts != null ? counts[1] : 0;
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getDescription(),
                docCount,
                convCount,
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }
}
