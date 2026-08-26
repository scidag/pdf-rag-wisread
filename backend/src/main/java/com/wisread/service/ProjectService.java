package com.wisread.service;

import com.wisread.dto.CreateProjectRequest;
import com.wisread.dto.UpdateProjectRequest;
import com.wisread.entity.Project;

import java.util.List;

public interface ProjectService {

    Project create(Long userId, CreateProjectRequest request);

    List<Project> list(Long userId);

    List<Project> listDeleted(Long userId);

    Project get(Long userId, Long projectId);

    Project update(Long userId, Long projectId, UpdateProjectRequest request);

    void delete(Long userId, Long projectId);

    void deleteBatch(Long userId, List<Long> projectIds);

    Project restore(Long userId, Long projectId);

    Project findOwnedProject(Long userId, Long projectId);

    long countDocuments(Long projectId);

    long countConversations(Long userId, Long projectId);
}
