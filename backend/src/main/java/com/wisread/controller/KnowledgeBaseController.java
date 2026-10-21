package com.wisread.controller;

import com.wisread.dto.KnowledgeBaseBatchDeleteRequest;
import com.wisread.dto.KnowledgeBaseCreateRequest;
import com.wisread.dto.KnowledgeBaseResponse;
import com.wisread.dto.KnowledgeBaseUpdateRequest;
import com.wisread.dto.KnowledgeRetrievalConfigResponse;
import com.wisread.dto.KnowledgeRetrievalConfigUpdateRequest;
import com.wisread.dto.KnowledgeRetrievalTestRequest;
import com.wisread.dto.KnowledgeRetrievalTestResponse;
import com.wisread.entity.KnowledgeBase;
import com.wisread.service.KnowledgeBaseService;
import com.wisread.service.KnowledgeRetrievalTestService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * 知识库（KnowledgeBase）控制器。
 * 负责知识库（独立于项目的文档容器，自带检索配置）相关的 REST 端点：
 * 创建知识库、列出知识库、查看回收站、查看知识库详情、更新知识库、发布、
 * 归档/取消归档、恢复知识库、删除知识库（软删除）、批量删除知识库。
 * 所有接口均需登录（从 Authentication 获取当前用户 ID）。
 * 基础路径：/api/v1/knowledge-bases
 */
@RestController
@RequestMapping("/api/v1/knowledge-bases")
public class KnowledgeBaseController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeBaseController.class);

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeRetrievalTestService knowledgeRetrievalTestService;

    public KnowledgeBaseController(
            KnowledgeBaseService knowledgeBaseService,
            KnowledgeRetrievalTestService knowledgeRetrievalTestService
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.knowledgeRetrievalTestService = knowledgeRetrievalTestService;
    }

    /** POST /api/v1/knowledge-bases：创建知识库。
     * 入参：@Valid KnowledgeBaseCreateRequest（知识库名称、描述），当前登录用户由 Authentication 解析得到。
     * 业务含义：为当前用户新建一个知识库（文档容器），初始状态为 DRAFT。
     * 返回：201 Created 及知识库信息（含文档数/分块数统计）。 */
    @PostMapping
    public ResponseEntity<KnowledgeBaseResponse> create(
            @Valid @RequestBody KnowledgeBaseCreateRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        KnowledgeBase knowledgeBase = knowledgeBaseService.create(userId, request);
        log.info("创建知识库 userId={} knowledgeBaseId={} name={}", userId, knowledgeBase.getId(), knowledgeBase.getName());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(knowledgeBase));
    }

    /** GET /api/v1/knowledge-bases：列出当前用户的知识库列表（不含回收站）。
     * 入参：无查询参数，当前登录用户由 Authentication 解析得到。
     * 业务含义：返回该用户所有未删除的知识库。
     * 返回：200 OK 及知识库信息列表（List<KnowledgeBaseResponse>）。 */
    @GetMapping
    public ResponseEntity<List<KnowledgeBaseResponse>> list(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        List<KnowledgeBase> knowledgeBases = knowledgeBaseService.list(userId);
        Map<Long, long[]> counts = knowledgeBaseService.countBatch(
                userId,
                knowledgeBases.stream().map(KnowledgeBase::getId).toList()
        );
        return ResponseEntity.ok(knowledgeBases.stream()
                .map(kb -> toResponse(kb, counts.getOrDefault(kb.getId(), new long[]{0, 0})))
                .toList());
    }

    /** GET /api/v1/knowledge-bases/deleted：列出当前用户的回收站知识库。
     * 入参：无查询参数，当前登录用户由 Authentication 解析得到。
     * 业务含义：返回该用户已软删除（可恢复）的知识库列表。
     * 返回：200 OK 及已删除知识库信息列表（List<KnowledgeBaseResponse>）。 */
    @GetMapping("/deleted")
    public ResponseEntity<List<KnowledgeBaseResponse>> listDeleted(Authentication authentication) {
        Long userId = (Long) authentication.getPrincipal();
        List<KnowledgeBase> knowledgeBases = knowledgeBaseService.listDeleted(userId);
        Map<Long, long[]> counts = knowledgeBaseService.countBatch(
                userId,
                knowledgeBases.stream().map(KnowledgeBase::getId).toList()
        );
        return ResponseEntity.ok(knowledgeBases.stream()
                .map(kb -> toResponse(kb, counts.getOrDefault(kb.getId(), new long[]{0, 0})))
                .toList());
    }

    /** GET /api/v1/knowledge-bases/{id}：获取单个知识库详情。
     * 入参：路径变量 id（知识库 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：返回指定知识库的完整信息（含状态、检索配置与文档数/分块数统计）。
     * 返回：200 OK 及知识库信息（KnowledgeBaseResponse）。 */
    @GetMapping("/{id}")
    public ResponseEntity<KnowledgeBaseResponse> get(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        KnowledgeBase knowledgeBase = knowledgeBaseService.get(userId, id);
        return ResponseEntity.ok(toResponse(knowledgeBase));
    }

    /** PATCH /api/v1/knowledge-bases/{id}：更新知识库信息。
     * 入参：路径变量 id（知识库 ID），@Valid KnowledgeBaseUpdateRequest（新名称/描述/检索配置），
     * 当前登录用户由 Authentication 解析得到。
     * 业务含义：修改指定知识库的基本信息与检索配置（仅覆盖请求中提供的字段）。
     * 返回：200 OK 及更新后的知识库信息（KnowledgeBaseResponse）。 */
    @PatchMapping("/{id}")
    public ResponseEntity<KnowledgeBaseResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeBaseUpdateRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        KnowledgeBase knowledgeBase = knowledgeBaseService.update(userId, id, request);
        log.info("更新知识库 userId={} knowledgeBaseId={}", userId, id);
        return ResponseEntity.ok(toResponse(knowledgeBase));
    }

    /** PATCH /api/v1/knowledge-bases/{id}/publish：发布知识库。
     * 入参：路径变量 id（知识库 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：将 DRAFT 知识库置为 ACTIVE；已 ACTIVE 时幂等返回。
     * 返回：200 OK 及发布后的知识库信息（KnowledgeBaseResponse）。 */
    @PatchMapping("/{id}/publish")
    public ResponseEntity<KnowledgeBaseResponse> publish(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        KnowledgeBase knowledgeBase = knowledgeBaseService.publish(userId, id);
        log.info("发布知识库 userId={} knowledgeBaseId={} status={}", userId, id, knowledgeBase.getStatus());
        return ResponseEntity.ok(toResponse(knowledgeBase));
    }

    /** PATCH /api/v1/knowledge-bases/{id}/archive：归档知识库。
     * 入参：路径变量 id（知识库 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：将 ACTIVE/DRAFT 知识库置为 ARCHIVED（归档优先，状态不再自动派生）。
     * 返回：200 OK 及归档后的知识库信息（KnowledgeBaseResponse）。 */
    @PatchMapping("/{id}/archive")
    public ResponseEntity<KnowledgeBaseResponse> archive(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        KnowledgeBase knowledgeBase = knowledgeBaseService.archive(userId, id);
        log.info("归档知识库 userId={} knowledgeBaseId={}", userId, id);
        return ResponseEntity.ok(toResponse(knowledgeBase));
    }

    /** PATCH /api/v1/knowledge-bases/{id}/unarchive：取消归档知识库。
     * 入参：路径变量 id（知识库 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：将 ARCHIVED 知识库恢复为 ACTIVE（spec 中归档/恢复二合一端点在此拆分为两个）。
     * 返回：200 OK 及取消归档后的知识库信息（KnowledgeBaseResponse）。 */
    @PatchMapping("/{id}/unarchive")
    public ResponseEntity<KnowledgeBaseResponse> unarchive(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        KnowledgeBase knowledgeBase = knowledgeBaseService.unarchive(userId, id);
        log.info("取消归档知识库 userId={} knowledgeBaseId={}", userId, id);
        return ResponseEntity.ok(toResponse(knowledgeBase));
    }

    /** PATCH /api/v1/knowledge-bases/{id}/restore：恢复回收站中的知识库。
     * 入参：路径变量 id（知识库 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：将已软删除的知识库恢复为正常状态。
     * 返回：200 OK 及恢复后的知识库信息（KnowledgeBaseResponse）。 */
    @PatchMapping("/{id}/restore")
    public ResponseEntity<KnowledgeBaseResponse> restore(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        KnowledgeBase knowledgeBase = knowledgeBaseService.restore(userId, id);
        log.info("恢复知识库 userId={} knowledgeBaseId={}", userId, id);
        return ResponseEntity.ok(toResponse(knowledgeBase));
    }

    /** DELETE /api/v1/knowledge-bases/{id}：删除知识库（软删除）。
     * 入参：路径变量 id（知识库 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：将指定知识库移入回收站（软删除），后续可恢复。
     * 返回：204 No Content。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        knowledgeBaseService.delete(userId, id);
        log.info("删除知识库（软删除） userId={} knowledgeBaseId={}", userId, id);
        return ResponseEntity.noContent().build();
    }

    /** POST /api/v1/knowledge-bases/batch-delete：批量删除知识库。
     * 入参：@Valid KnowledgeBaseBatchDeleteRequest（待删除知识库 ID 列表，单次最多 100 个），
     * 当前登录用户由 Authentication 解析得到。
     * 业务含义：一次性将多个知识库移入回收站（软删除）。
     * 返回：204 No Content。 */
    @PostMapping("/batch-delete")
    public ResponseEntity<Void> deleteBatch(
            @Valid @RequestBody KnowledgeBaseBatchDeleteRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        knowledgeBaseService.deleteBatch(userId, request.ids());
        log.info("批量删除知识库 userId={} count={} ids={}", userId, request.ids().size(), request.ids());
        return ResponseEntity.noContent().build();
    }

    /** GET /api/v1/knowledge-bases/{id}/retrieval-config：查询知识库检索配置。
     * 入参：路径变量 id（知识库 ID），当前登录用户由 Authentication 解析得到。
     * 业务含义：返回该知识库当前生效的检索配置（topK/相似度阈值/重排开关/rerankK）。
     * 返回：200 OK 及检索配置（KnowledgeRetrievalConfigResponse）；越权 403。 */
    @GetMapping("/{id}/retrieval-config")
    public ResponseEntity<KnowledgeRetrievalConfigResponse> getRetrievalConfig(
            @PathVariable Long id,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(knowledgeBaseService.getRetrievalConfig(userId, id));
    }

    /** PATCH /api/v1/knowledge-bases/{id}/retrieval-config：部分更新知识库检索配置。
     * 入参：路径变量 id（知识库 ID），KnowledgeRetrievalConfigUpdateRequest（仅覆盖提供的字段），
     * 当前登录用户由 Authentication 解析得到。
     * 业务含义：修改知识库检索配置（仅作用于知识库检索路径，不影响项目检索链路）。
     * 校验失败返回 400 与中文错误消息；越权返回 403。
     * 返回：200 OK 及更新后的检索配置（KnowledgeRetrievalConfigResponse）。 */
    @PatchMapping("/{id}/retrieval-config")
    public ResponseEntity<KnowledgeRetrievalConfigResponse> updateRetrievalConfig(
            @PathVariable Long id,
            @RequestBody KnowledgeRetrievalConfigUpdateRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        KnowledgeRetrievalConfigResponse response = knowledgeBaseService.updateRetrievalConfig(userId, id, request);
        log.info("更新知识库检索配置 userId={} knowledgeBaseId={}", userId, id);
        return ResponseEntity.ok(response);
    }

    /** POST /api/v1/knowledge-bases/{id}/retrieval-test：知识库检索测试（RAG 全链路演练）。
     * 入参：路径变量 id（知识库 ID），@Valid KnowledgeRetrievalTestRequest（测试问题，非空），
     * 当前登录用户由 Authentication 解析得到。
     * 业务含义：按该库当前检索配置执行一次"改写→召回→重排→生成"演练，
     * 返回各阶段明细、配置快照与耗时；召回口径与 Chat 绑库问答完全一致。
     * 返回：200 OK 及分阶段测试结果（KnowledgeRetrievalTestResponse）；越权 403。 */
    @PostMapping("/{id}/retrieval-test")
    public ResponseEntity<KnowledgeRetrievalTestResponse> retrievalTest(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeRetrievalTestRequest request,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(knowledgeRetrievalTestService.test(userId, id, request));
    }

    // 将 KnowledgeBase 实体转换为带统计信息（文档数/分块数）的 KnowledgeBaseResponse DTO
    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase) {
        long documentCount = knowledgeBaseService.countDocuments(knowledgeBase.getId());
        long chunkCount = knowledgeBaseService.countChunks(knowledgeBase.getId());
        return toResponse(knowledgeBase, new long[]{documentCount, chunkCount});
    }

    private KnowledgeBaseResponse toResponse(KnowledgeBase knowledgeBase, long[] counts) {
        long documentCount = counts != null ? counts[0] : 0;
        long chunkCount = counts != null ? counts[1] : 0;
        return new KnowledgeBaseResponse(
                knowledgeBase.getId(),
                knowledgeBase.getUserId(),
                knowledgeBase.getName(),
                knowledgeBase.getDescription(),
                knowledgeBase.getStatus(),
                knowledgeBase.getTopK(),
                knowledgeBase.getSimilarityThreshold(),
                knowledgeBase.getRerankEnabled(),
                knowledgeBase.getRerankK(),
                knowledgeBase.getAgentEnabled(),
                knowledgeBase.getMaxIterations(),
                documentCount,
                chunkCount,
                knowledgeBase.getCreatedAt(),
                knowledgeBase.getUpdatedAt()
        );
    }
}
