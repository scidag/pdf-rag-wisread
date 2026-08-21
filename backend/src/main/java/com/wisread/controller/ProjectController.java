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

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ResponseEntity<ProjectResponse> create(
            @Valid @RequestBody CreateProjectRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        Project project = projectService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(userId, project));
    }

    @GetMapping
    public ResponseEntity<List<ProjectResponse>> list(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        List<Project> projects = projectService.list(userId);
        return ResponseEntity.ok(projects.stream().map(p -> toResponse(userId, p)).toList());
    }

    @GetMapping("/deleted")
    public ResponseEntity<List<ProjectResponse>> listDeleted(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        List<Project> projects = projectService.listDeleted(userId);
        return ResponseEntity.ok(projects.stream().map(p -> toResponse(userId, p)).toList());
    }

    @GetMapping("/{projectId}")
    public ResponseEntity<ProjectResponse> get(
            @PathVariable Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        Project project = projectService.get(userId, projectId);
        return ResponseEntity.ok(toResponse(userId, project));
    }

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

    @PatchMapping("/{projectId}/restore")
    public ResponseEntity<ProjectResponse> restore(
            @PathVariable Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        Project project = projectService.restore(userId, projectId);
        return ResponseEntity.ok(toResponse(userId, project));
    }

    @DeleteMapping("/{projectId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        projectService.delete(userId, projectId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/batch-delete")
    public ResponseEntity<Void> deleteBatch(
            @Valid @RequestBody BatchDeleteRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        projectService.deleteBatch(userId, request.ids());
        return ResponseEntity.noContent().build();
    }

    private ProjectResponse toResponse(Long userId, Project project) {
        long docCount = projectService.countDocuments(project.getId());
        long convCount = projectService.countConversations(userId, project.getId());
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
