package com.wisread.service;

import com.wisread.dto.KnowledgeBaseCreateRequest;
import com.wisread.dto.KnowledgeBaseUpdateRequest;
import com.wisread.dto.KnowledgeRetrievalConfigResponse;
import com.wisread.dto.KnowledgeRetrievalConfigUpdateRequest;
import com.wisread.entity.KnowledgeBase;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 知识库（KnowledgeBase）业务服务接口。
 * 职责：管理用户维度的知识库资源，提供创建、列表、详情、更新、发布、归档/取消归档、
 * 软删除、回收站恢复、批量删除等能力，并统计知识库下的文档与分块数量。
 * 所有方法均要求传入 userId 以做归属校验；删除采用软删除（逻辑删除）以支持回收站。
 */
public interface KnowledgeBaseService {

    /**
     * 创建知识库，绑定到指定用户，初始状态为 DRAFT（草稿）。
     *
     * @param userId  创建者 ID
     * @param request 创建请求（含名称、描述）
     * @return 已持久化的知识库实体
     */
    KnowledgeBase create(Long userId, KnowledgeBaseCreateRequest request);

    /**
     * 列出该用户未删除的知识库（按创建时间倒序）。
     */
    List<KnowledgeBase> list(Long userId);

    /**
     * 列出该用户已软删除的知识库（回收站，按删除时间倒序）。
     */
    List<KnowledgeBase> listDeleted(Long userId);

    /**
     * 获取单个知识库（需归属校验）。
     */
    KnowledgeBase get(Long userId, Long knowledgeBaseId);

    /**
     * 更新知识库（仅覆盖请求中提供的字段：名称/描述/检索配置）。
     */
    KnowledgeBase update(Long userId, Long knowledgeBaseId, KnowledgeBaseUpdateRequest request);

    /**
     * 发布知识库：DRAFT → ACTIVE；已 ACTIVE 时幂等返回；
     * ARCHIVED 状态不可发布（需先取消归档）。
     */
    KnowledgeBase publish(Long userId, Long knowledgeBaseId);

    /**
     * 归档知识库：ACTIVE/DRAFT → ARCHIVED；PROCESSING 状态不可归档。
     */
    KnowledgeBase archive(Long userId, Long knowledgeBaseId);

    /**
     * 取消归档知识库：ARCHIVED → ACTIVE；非归档状态时原样返回（幂等）。
     */
    KnowledgeBase unarchive(Long userId, Long knowledgeBaseId);

    /**
     * 软删除单个知识库（逻辑删除，不物理移除数据）。
     */
    void delete(Long userId, Long knowledgeBaseId);

    /**
     * 恢复已软删除的知识库。
     */
    KnowledgeBase restore(Long userId, Long knowledgeBaseId);

    /**
     * 批量软删除多个知识库（自动去重；单次最多 100 个）。
     */
    void deleteBatch(Long userId, List<Long> knowledgeBaseIds);

    /**
     * 校验知识库归属并返回实体；越权或不存在则抛异常。
     */
    KnowledgeBase findOwnedKnowledgeBase(Long userId, Long knowledgeBaseId);

    /**
     * 统计某知识库下的文档数量。
     */
    long countDocuments(Long knowledgeBaseId);

    /**
     * 统计某知识库下的分块（chunk）数量。
     */
    long countChunks(Long knowledgeBaseId);

    /**
     * 批量统计多个知识库的文档数与分块数（列表接口一次 SQL 完成）。
     *
     * @return key 为知识库 id，value 为 long[]{documentCount, chunkCount}
     */
    Map<Long, long[]> countBatch(Long userId, Collection<Long> knowledgeBaseIds);

    /**
     * 依据知识库下文档的处理状态派生知识库状态（本批不接线，后续批次在文档状态变化处调用）：
     * 存在 PROCESSING/UPLOADED 文档 → PROCESSING；无文档保持原状态；
     * 全部文档 INDEXED 且当前为 DRAFT → ACTIVE；ARCHIVED 时不自动变更（归档优先）。
     *
     * @param knowledgeBaseId 知识库 id
     * @return 派生后的知识库实体（状态有变化时已持久化）
     */
    KnowledgeBase refreshDerivedStatus(Long knowledgeBaseId);

    /**
     * 查询知识库当前生效的检索配置（topK/相似度阈值/重排开关/rerankK）。
     * 仅知识库所有者可查（越权 403）。
     */
    KnowledgeRetrievalConfigResponse getRetrievalConfig(Long userId, Long knowledgeBaseId);

    /**
     * 部分更新知识库检索配置：仅覆盖请求中提供的字段，其余保持不变。
     * 校验（中文消息）：top_k 取值 1~50、similarity_threshold 取值 0~1、
     * rerank_k ≥ 1、rerank_k 不能大于 top_k（跨字段，按合并后的生效值校验）。
     * 仅作用于知识库检索路径（Chat 绑库问答与检索测试），不影响项目检索链路。
     */
    KnowledgeRetrievalConfigResponse updateRetrievalConfig(
            Long userId,
            Long knowledgeBaseId,
            KnowledgeRetrievalConfigUpdateRequest request
    );
}
