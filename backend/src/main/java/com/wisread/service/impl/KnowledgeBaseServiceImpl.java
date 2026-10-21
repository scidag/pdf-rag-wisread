package com.wisread.service.impl;

import com.wisread.dto.KnowledgeBaseCreateRequest;
import com.wisread.dto.KnowledgeBaseUpdateRequest;
import com.wisread.dto.KnowledgeRetrievalConfigResponse;
import com.wisread.dto.KnowledgeRetrievalConfigUpdateRequest;
import com.wisread.entity.KnowledgeBase;
import com.wisread.exception.ApiException;
import com.wisread.repository.KnowledgeBaseRepository;
import com.wisread.service.KnowledgeBaseService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库（KnowledgeBase）业务服务的实现，负责知识库维度的增删改查、
 * 发布/归档等状态流转与软删除管理。
 * 实现要点：所有写操作均为事务方法；删除采用“软删除”（写 deleted_at 时间戳）以支持回收站；
 * 任何跨用户访问都先经 {@link #findOwnedKnowledgeBase} 校验归属，杜绝越权访问他人知识库；
 * {@link #refreshDerivedStatus} 依据文档处理状态派生知识库状态（供后续批次在文档状态变化处调用）。
 */
@Service
public class KnowledgeBaseServiceImpl implements KnowledgeBaseService {

    // 单次批量删除的知识库数量上限
    private static final int BATCH_DELETE_LIMIT = 100;

    private final KnowledgeBaseRepository knowledgeBaseRepository;

    public KnowledgeBaseServiceImpl(KnowledgeBaseRepository knowledgeBaseRepository) {
        this.knowledgeBaseRepository = knowledgeBaseRepository;
    }

    /**
     * 创建知识库。校验名称非空，绑定所属用户，初始状态 DRAFT，插入后返回持久化实体。
     * 检索配置（topK/相似度阈值/重排）使用实体默认值。
     */
    @Override
    @Transactional
    public KnowledgeBase create(Long userId, KnowledgeBaseCreateRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "知识库名称不能为空");
        }
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        knowledgeBase.setUserId(userId);
        knowledgeBase.setName(request.name().trim());
        knowledgeBase.setDescription(request.description());
        knowledgeBaseRepository.insert(knowledgeBase);
        return knowledgeBase;
    }

    /**
     * 列出当前用户未删除的知识库，按创建时间倒序。
     */
    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBase> list(Long userId) {
        return knowledgeBaseRepository.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId);
    }

    /**
     * 列出当前用户已软删除（进入回收站）的知识库，按删除时间倒序。
     */
    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeBase> listDeleted(Long userId) {
        return knowledgeBaseRepository.findByUserIdAndDeletedAtIsNotNullOrderByDeletedAtDesc(userId);
    }

    /**
     * 获取单个知识库（带归属校验）。
     */
    @Override
    @Transactional(readOnly = true)
    public KnowledgeBase get(Long userId, Long knowledgeBaseId) {
        return findOwnedKnowledgeBase(userId, knowledgeBaseId);
    }

    /**
     * 更新知识库。仅覆盖请求中提供的非空字段（名称/描述/检索配置），保持其余字段不变。
     */
    @Override
    @Transactional
    public KnowledgeBase update(Long userId, Long knowledgeBaseId, KnowledgeBaseUpdateRequest request) {
        KnowledgeBase knowledgeBase = findOwnedKnowledgeBase(userId, knowledgeBaseId);
        if (request.name() != null && !request.name().isBlank()) {
            knowledgeBase.setName(request.name().trim());
        }
        if (request.description() != null) {
            knowledgeBase.setDescription(request.description());
        }
        if (request.topK() != null) {
            knowledgeBase.setTopK(request.topK());
        }
        if (request.similarityThreshold() != null) {
            knowledgeBase.setSimilarityThreshold(request.similarityThreshold());
        }
        if (request.rerankEnabled() != null) {
            knowledgeBase.setRerankEnabled(request.rerankEnabled());
        }
        if (request.rerankK() != null) {
            knowledgeBase.setRerankK(request.rerankK());
        }
        // agent 开关防覆盖：仅请求显式携带（非空）时才更新，不携带则保持原值
        if (request.agentEnabled() != null) {
            knowledgeBase.setAgentEnabled(request.agentEnabled());
        }
        knowledgeBaseRepository.updateById(knowledgeBase);
        return knowledgeBase;
    }

    /**
     * 发布知识库：DRAFT/PROCESSING → ACTIVE；已 ACTIVE 时幂等返回；
     * ARCHIVED 状态不可直接发布，需先取消归档（409）。
     */
    @Override
    @Transactional
    public KnowledgeBase publish(Long userId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = findOwnedKnowledgeBase(userId, knowledgeBaseId);
        if (KnowledgeBase.STATUS_ACTIVE.equals(knowledgeBase.getStatus())) {
            return knowledgeBase;
        }
        if (KnowledgeBase.STATUS_ARCHIVED.equals(knowledgeBase.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "已归档的知识库不能发布，请先取消归档");
        }
        knowledgeBase.setStatus(KnowledgeBase.STATUS_ACTIVE);
        knowledgeBaseRepository.updateById(knowledgeBase);
        return knowledgeBase;
    }

    /**
     * 归档知识库：ACTIVE/DRAFT → ARCHIVED；处理中（PROCESSING）不可归档（409）；
     * 已归档时幂等返回。归档后状态不再被文档状态自动派生覆盖。
     */
    @Override
    @Transactional
    public KnowledgeBase archive(Long userId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = findOwnedKnowledgeBase(userId, knowledgeBaseId);
        if (KnowledgeBase.STATUS_ARCHIVED.equals(knowledgeBase.getStatus())) {
            return knowledgeBase;
        }
        if (KnowledgeBase.STATUS_PROCESSING.equals(knowledgeBase.getStatus())) {
            throw new ApiException(HttpStatus.CONFLICT, "知识库正在处理中，暂不能归档");
        }
        knowledgeBase.setStatus(KnowledgeBase.STATUS_ARCHIVED);
        knowledgeBaseRepository.updateById(knowledgeBase);
        return knowledgeBase;
    }

    /**
     * 取消归档知识库：ARCHIVED → ACTIVE；非归档状态时原样返回（幂等）。
     */
    @Override
    @Transactional
    public KnowledgeBase unarchive(Long userId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = findOwnedKnowledgeBase(userId, knowledgeBaseId);
        if (!KnowledgeBase.STATUS_ARCHIVED.equals(knowledgeBase.getStatus())) {
            return knowledgeBase;
        }
        knowledgeBase.setStatus(KnowledgeBase.STATUS_ACTIVE);
        knowledgeBaseRepository.updateById(knowledgeBase);
        return knowledgeBase;
    }

    /**
     * 软删除单个知识库：仅记录删除时间，不物理删除，便于后续恢复。
     */
    @Override
    @Transactional
    public void delete(Long userId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = findOwnedKnowledgeBase(userId, knowledgeBaseId);
        knowledgeBase.setDeletedAt(Instant.now());
        knowledgeBaseRepository.updateById(knowledgeBase);
    }

    /**
     * 恢复已软删除的知识库：清空 deleted_at 使其重新出现在正常列表。
     * 仅针对已删除状态的知识库，否则视为不存在而抛 NOT_FOUND。
     */
    @Override
    @Transactional
    public KnowledgeBase restore(Long userId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository
                .findByUserIdAndIdAndDeletedAtIsNotNull(userId, knowledgeBaseId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "知识库不存在或未删除"));
        knowledgeBase.setDeletedAt(null);
        knowledgeBaseRepository.updateById(knowledgeBase);
        return knowledgeBase;
    }

    /**
     * 批量软删除知识库：单次最多 100 个（超限报错），先去重再逐个删除。
     */
    @Override
    @Transactional
    public void deleteBatch(Long userId, List<Long> knowledgeBaseIds) {
        if (knowledgeBaseIds != null && knowledgeBaseIds.size() > BATCH_DELETE_LIMIT) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "单次最多删除 100 个知识库");
        }
        for (Long knowledgeBaseId : knowledgeBaseIds.stream().distinct().toList()) {
            delete(userId, knowledgeBaseId);
        }
    }

    /**
     * 校验知识库归属并返回实体。所有需要操作具体知识库的方法都先经此方法，
     * 通过 user_id + id + 未删除三条件定位；未命中时二次区分"自己的库已软删除"
     * （404"知识库不存在或已删除"，引导回收站恢复）与"越权/不存在"（403，避免泄露存在性），
     * 与会话切库（requireBindableKnowledgeBase）的错误口径保持一致。
     */
    @Override
    public KnowledgeBase findOwnedKnowledgeBase(Long userId, Long knowledgeBaseId) {
        return knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNull(userId, knowledgeBaseId)
                .orElseGet(() -> {
                    if (knowledgeBaseRepository.findByUserIdAndIdAndDeletedAtIsNotNull(userId, knowledgeBaseId)
                            .isPresent()) {
                        throw new ApiException(HttpStatus.NOT_FOUND, "知识库不存在或已删除");
                    }
                    throw new ApiException(HttpStatus.FORBIDDEN, "无权访问该知识库");
                });
    }

    /**
     * 统计某知识库下的文档数量（documents 按 knowledge_base_id 计数）。
     */
    @Override
    @Transactional(readOnly = true)
    public long countDocuments(Long knowledgeBaseId) {
        return knowledgeBaseRepository.countDocumentsByKnowledgeBaseId(knowledgeBaseId);
    }

    /**
     * 统计某知识库下的分块数量（document_chunks 关联 documents 按 knowledge_base_id 计数）。
     */
    @Override
    @Transactional(readOnly = true)
    public long countChunks(Long knowledgeBaseId) {
        return knowledgeBaseRepository.countChunksByKnowledgeBaseId(knowledgeBaseId);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, long[]> countBatch(Long userId, Collection<Long> knowledgeBaseIds) {
        Map<Long, long[]> result = new HashMap<>();
        for (Map<String, Object> row : knowledgeBaseRepository.countStatsByUserId(userId)) {
            Long kbId = ((Number) row.get("kb_id")).longValue();
            if (!knowledgeBaseIds.contains(kbId)) {
                continue;
            }
            long documentCount = ((Number) row.get("document_count")).longValue();
            long chunkCount = ((Number) row.get("chunk_count")).longValue();
            result.put(kbId, new long[]{documentCount, chunkCount});
        }
        return result;
    }

    /**
     * 依据知识库下文档的处理状态派生知识库状态（本批不接线，后续批次在文档状态变化处调用）。
     * 派生规则：
     * <ul>
     *   <li>存在 PROCESSING 或 UPLOADED 文档 → 状态置为 PROCESSING；</li>
     *   <li>无文档 → 保持原状态（DRAFT 知识库无文档仍为 DRAFT）；</li>
     *   <li>全部文档 INDEXED 且当前状态为 DRAFT → ACTIVE；</li>
     *   <li>当前状态为 ARCHIVED → 不自动变更（归档优先）。</li>
     * </ul>
     * 仅在状态实际变化时写库。
     */
    @Override
    @Transactional
    public KnowledgeBase refreshDerivedStatus(Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = knowledgeBaseRepository.findById(knowledgeBaseId).orElse(null);
        if (knowledgeBase == null || knowledgeBase.getDeletedAt() != null) {
            return knowledgeBase;
        }
        // 归档优先：归档状态不随文档状态自动变更
        if (KnowledgeBase.STATUS_ARCHIVED.equals(knowledgeBase.getStatus())) {
            return knowledgeBase;
        }
        List<Map<String, Object>> statusCounts =
                knowledgeBaseRepository.countDocumentStatusByKnowledgeBaseId(knowledgeBaseId);
        // 无文档保持原状态（DRAFT 知识库无文档仍为 DRAFT）
        if (statusCounts.isEmpty()) {
            return knowledgeBase;
        }
        boolean hasProcessingLike = false;
        boolean allIndexed = true;
        for (Map<String, Object> row : statusCounts) {
            String documentStatus = (String) row.get("status");
            if ("PROCESSING".equals(documentStatus) || "UPLOADED".equals(documentStatus)) {
                hasProcessingLike = true;
            }
            if (!"INDEXED".equals(documentStatus)) {
                allIndexed = false;
            }
        }
        String derivedStatus;
        if (hasProcessingLike) {
            derivedStatus = KnowledgeBase.STATUS_PROCESSING;
        } else if (allIndexed && (KnowledgeBase.STATUS_DRAFT.equals(knowledgeBase.getStatus())
                || KnowledgeBase.STATUS_PROCESSING.equals(knowledgeBase.getStatus()))) {
            // 全部文档索引完成：DRAFT（首次建库完成）或 PROCESSING（处理中收尾）都流转到 ACTIVE，
            // 否则库会永久卡死在 PROCESSING（DRAFT→PROCESSING→? 无出口）
            derivedStatus = KnowledgeBase.STATUS_ACTIVE;
        } else {
            return knowledgeBase;
        }
        if (!derivedStatus.equals(knowledgeBase.getStatus())) {
            knowledgeBase.setStatus(derivedStatus);
            knowledgeBaseRepository.updateById(knowledgeBase);
        }
        return knowledgeBase;
    }

    /**
     * 查询知识库当前生效的检索配置。归属校验复用 {@link #findOwnedKnowledgeBase}（越权 403）。
     */
    @Override
    @Transactional(readOnly = true)
    public KnowledgeRetrievalConfigResponse getRetrievalConfig(Long userId, Long knowledgeBaseId) {
        KnowledgeBase knowledgeBase = findOwnedKnowledgeBase(userId, knowledgeBaseId);
        return toRetrievalConfigResponse(knowledgeBase);
    }

    /**
     * 部分更新知识库检索配置：先做单字段取值范围校验，再按"合并后的生效值"做
     * 跨字段校验（rerank_k 不能大于 top_k，覆盖只改其中一个字段的场景），最后仅覆盖提供的字段。
     * 仅作用于知识库检索路径，不改动项目检索链路的任何默认值。
     */
    @Override
    @Transactional
    public KnowledgeRetrievalConfigResponse updateRetrievalConfig(
            Long userId,
            Long knowledgeBaseId,
            KnowledgeRetrievalConfigUpdateRequest request
    ) {
        KnowledgeBase knowledgeBase = findOwnedKnowledgeBase(userId, knowledgeBaseId);
        if (request.topK() != null && (request.topK() < 1 || request.topK() > 50)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "top_k 取值范围为 1~50");
        }
        if (request.similarityThreshold() != null
                && (request.similarityThreshold() < 0 || request.similarityThreshold() > 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "similarity_threshold 取值范围为 0~1");
        }
        if (request.rerankK() != null && request.rerankK() < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "rerank_k 取值范围最小为 1");
        }
        // 跨字段校验按合并后的生效值：只传 rerank_k 时与库内现有 top_k 比，只传 top_k 时与库内现有 rerank_k 比
        Integer effectiveTopK = request.topK() != null ? request.topK() : knowledgeBase.getTopK();
        Integer effectiveRerankK = request.rerankK() != null ? request.rerankK() : knowledgeBase.getRerankK();
        if (effectiveTopK != null && effectiveRerankK != null && effectiveRerankK > effectiveTopK) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "rerank_k 不能大于 top_k");
        }
        if (request.topK() != null) {
            knowledgeBase.setTopK(request.topK());
        }
        if (request.similarityThreshold() != null) {
            knowledgeBase.setSimilarityThreshold(request.similarityThreshold());
        }
        if (request.rerankEnabled() != null) {
            knowledgeBase.setRerankEnabled(request.rerankEnabled());
        }
        if (request.rerankK() != null) {
            knowledgeBase.setRerankK(request.rerankK());
        }
        knowledgeBaseRepository.updateById(knowledgeBase);
        return toRetrievalConfigResponse(knowledgeBase);
    }

    /**
     * 将知识库实体的检索配置字段转换为响应 DTO。
     */
    private KnowledgeRetrievalConfigResponse toRetrievalConfigResponse(KnowledgeBase knowledgeBase) {
        return new KnowledgeRetrievalConfigResponse(
                knowledgeBase.getTopK(),
                knowledgeBase.getSimilarityThreshold(),
                knowledgeBase.getRerankEnabled(),
                knowledgeBase.getRerankK()
        );
    }
}
