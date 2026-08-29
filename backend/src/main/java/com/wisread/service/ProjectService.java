package com.wisread.service;

import com.wisread.dto.CreateProjectRequest;
import com.wisread.dto.UpdateProjectRequest;
import com.wisread.entity.Project;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 项目（Project）业务服务接口。
 * 职责：管理用户维度的项目资源，提供创建、列表、详情、更新、软删除、批量删除、
 * 回收站恢复等 CRUD 能力，并统计项目下的文档与会话数量。所有方法均要求传入 userId
 * 以做归属校验，项目删除采用软删除（逻辑删除）以支持回收站。
 */
public interface ProjectService {

    /**
     * 创建项目，绑定到指定用户。
     *
     * @param userId  创建者 ID
     * @param request 创建请求（含名称、描述）
     * @return 已持久化的项目实体
     */
    Project create(Long userId, CreateProjectRequest request);

    /**
     * 列出该用户未删除的项目（按创建时间倒序）。
     */
    List<Project> list(Long userId);

    /**
     * 列出该用户已软删除的项目（回收站，按删除时间倒序）。
     */
    List<Project> listDeleted(Long userId);

    /**
     * 获取单个项目（需归属校验）。
     */
    Project get(Long userId, Long projectId);

    /**
     * 更新项目（仅覆盖请求中提供的字段）。
     */
    Project update(Long userId, Long projectId, UpdateProjectRequest request);

    /**
     * 软删除单个项目（逻辑删除，不物理移除数据）。
     */
    void delete(Long userId, Long projectId);

    /**
     * 批量软删除多个项目（自动去重）。
     */
    void deleteBatch(Long userId, List<Long> projectIds);

    /**
     * 恢复已软删除的项目。
     */
    Project restore(Long userId, Long projectId);

    /**
     * 校验项目归属并返回实体；越权或不存在则抛异常。
     */
    Project findOwnedProject(Long userId, Long projectId);

    /**
     * 统计某项目下的文档数量。
     */
    long countDocuments(Long projectId);

    /**
     * 统计某用户在某项目下的会话数量。
     */
    long countConversations(Long userId, Long projectId);

    /**
     * 批量统计多个项目的文档数与会话数（列表接口一次 SQL 完成）。
     */
    Map<Long, long[]> countBatch(Long userId, Collection<Long> projectIds);
}
