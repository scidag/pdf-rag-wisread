package com.wisread.service.impl;

import com.wisread.entity.KnowledgeDocument;
import com.wisread.exception.ApiException;
import com.wisread.model.DocumentParsingResult;
import com.wisread.model.PageText;
import com.wisread.model.TextChunk;
import com.wisread.repository.KnowledgeDocumentRepository;
import com.wisread.service.ChunkingService;
import com.wisread.service.DocumentParsingService;
import com.wisread.service.EmbeddingService;
import com.wisread.service.KnowledgeBaseService;
import com.wisread.service.KnowledgeDocumentProcessingService;
import com.wisread.service.KnowledgeVectorIndexingService;
import com.wisread.service.MinioStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库文档处理异步流水线实现。
 *
 * <p>行为对齐现有 {@code DocumentProcessingServiceImpl}（失败自动重试 1 次、
 * 定时恢复卡死任务），但操作的是知识库专属表（knowledge_documents / knowledge_chunks），
 * 状态机统一为 UPLOADED → PROCESSING → INDEXED / FAILED，且每次状态变化都调用
 * {@link KnowledgeBaseService#refreshDerivedStatus} 派生知识库整体状态。
 *
 * <p>实现要点（@Async 后台执行，端到端数据流）：
 * <ol>
 *   <li>CAS 抢占：仅当文档仍为 UPLOADED 时原子置为 PROCESSING，避免并发重复处理。</li>
 *   <li>从 MinIO 取回文件字节，按文档类型抽取文本（DocumentParsingService）。</li>
 *   <li>逐页分块（ChunkingService），累加 token 数并做全局上限校验（50 万，超出 422 失败）。</li>
 *   <li>对全部文本做 embedding，调用 KnowledgeVectorIndexingService 写入 knowledge_chunks。</li>
 *   <li>成功后状态推进到 INDEXED，回写页数、token 数、embedding 模型版本。</li>
 * </ol>
 *
 * <p>失败重试：首次失败（retryCount &lt; 1）清理已写向量、回退 UPLOADED 并立即重试一次；
 * 仍失败则置为 FAILED 并保留错误信息。无文本/扫描件文档直接以 422 失败。
 */
@Service
public class KnowledgeDocumentProcessingServiceImpl implements KnowledgeDocumentProcessingService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentProcessingServiceImpl.class);

    // 单文档 token 总量上限（50 万），超出视为不合规文档，直接失败
    private static final int MAX_TOKEN_COUNT = 500_000;
    // 处理总尝试次数上限（首次 + 1 次重试）
    private static final int MAX_ATTEMPTS = 2;

    private final KnowledgeDocumentRepository knowledgeDocumentRepository;
    private final KnowledgeVectorIndexingService knowledgeVectorIndexingService;
    private final DocumentParsingService documentParsingService;
    private final ChunkingService chunkingService;
    private final EmbeddingService embeddingService;
    private final MinioStorageService minioStorageService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final String embeddingModelVersion;

    // 卡死任务判定阈值：PROCESSING 状态停留超过该时长视为执行超时
    @Value("${wisread.document.run-timeout-ms:600000}")
    private long runTimeoutMs;

    public KnowledgeDocumentProcessingServiceImpl(
            KnowledgeDocumentRepository knowledgeDocumentRepository,
            KnowledgeVectorIndexingService knowledgeVectorIndexingService,
            DocumentParsingService documentParsingService,
            ChunkingService chunkingService,
            EmbeddingService embeddingService,
            MinioStorageService minioStorageService,
            KnowledgeBaseService knowledgeBaseService,
            @Value("${spring.ai.dashscope.embedding.options.model:qwen3.7-text-embedding-flash}") String embeddingModelVersion
    ) {
        this.knowledgeDocumentRepository = knowledgeDocumentRepository;
        this.knowledgeVectorIndexingService = knowledgeVectorIndexingService;
        this.documentParsingService = documentParsingService;
        this.chunkingService = chunkingService;
        this.embeddingService = embeddingService;
        this.minioStorageService = minioStorageService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.embeddingModelVersion = embeddingModelVersion;
    }

    /**
     * 异步处理知识库文档入口。
     *
     * <p>标注 {@code @Async("documentTaskExecutor")}，调用方（上传/重新解析接口）不会阻塞，
     * 真正的流水线在 {@code documentTaskExecutor} 线程池中执行。
     */
    @Override
    @Async("documentTaskExecutor")
    public void processDocument(Long documentId, Long userId) {
        processInternal(documentId, userId);
    }

    /**
     * 处理流水线核心。
     *
     * <p>依次完成：CAS 抢占 UPLOADED→PROCESSING → MinIO 取文件 → 解析定位片段 →
     * 分块并累计 token → token 上限校验 → 批量 embedding → 写入 knowledge_chunks →
     * 状态推进到 INDEXED；每个状态变化点都刷新知识库派生状态。
     */
    private void processInternal(Long documentId, Long userId) {
        long startNanos = System.nanoTime();
        // 异步线程中文档可能已被并发删除，查不到时直接放弃本次处理
        KnowledgeDocument document = knowledgeDocumentRepository.findById(documentId).orElse(null);
        if (document == null) {
            log.debug("知识库文档处理放弃：文档不存在（可能已被删除） documentId={} userId={}", documentId, userId);
            return;
        }
        // 原子抢占 UPLOADED -> PROCESSING，避免并发重复处理；抢占失败说明他人已接管
        if (knowledgeDocumentRepository.claimProcessing(documentId) == 0) {
            log.info("知识库文档处理跳过：已被其他线程抢占 documentId={}", documentId);
            return;
        }
        log.info("知识库文档处理开始 documentId={} knowledgeBaseId={} userId={} filename={} attempt={}",
                documentId, document.getKnowledgeBaseId(), userId, document.getFilename(), document.getRetryCount());
        document.setStatus(KnowledgeDocument.STATUS_PROCESSING);
        document.setErrorMessage(null);
        knowledgeDocumentRepository.updateById(document);
        knowledgeBaseService.refreshDerivedStatus(document.getKnowledgeBaseId());

        try {
            // 幂等：重新处理前清理旧向量，避免部分失败后残留脏数据
            knowledgeVectorIndexingService.deleteByDocumentId(documentId);
            // 从对象存储取回原始字节
            byte[] fileBytes = minioStorageService.getObject(document.getFileKey());
            log.debug("知识库文档 MinIO 取件完成 documentId={} bytes={}", documentId, fileBytes.length);
            // 按文档类型抽取定位片段
            DocumentParsingResult parsingResult = documentParsingService.parse(fileBytes, document.getFileType());
            List<PageText> pages = parsingResult.segments();
            log.info("知识库文档解析完成 documentId={} locations={}", documentId, parsingResult.locationCount());
            // 所有定位片段均为空文本，视为扫描件/空文档，直接失败
            if (pages.stream().allMatch(page -> page.text().isBlank())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "文档为扫描件或无文本内容，无法解析");
            }

            // 逐页分块，使用 nextChunkIndex 维持全局块序号连续
            List<TextChunk> chunks = new ArrayList<>();
            int nextChunkIndex = 0;
            int totalTokens = 0;
            for (PageText page : pages) {
                List<TextChunk> pageChunks = chunkingService.splitPage(page, nextChunkIndex);
                chunks.addAll(pageChunks);
                nextChunkIndex += pageChunks.size();
                totalTokens += pageChunks.stream().mapToInt(TextChunk::tokenCount).sum();
            }
            log.info("知识库文档分块完成 documentId={} chunks={} totalTokens={}", documentId, chunks.size(), totalTokens);
            // 全局 token 上限校验，超出直接失败避免后续成本失控
            if (totalTokens > MAX_TOKEN_COUNT) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "文档超过 500000 token 上限，无法索引");
            }

            // 批量向量化并写入 knowledge_chunks
            List<String> texts = chunks.stream().map(TextChunk::content).toList();
            List<float[]> embeddings = embeddingService.embed(texts, userId);
            knowledgeVectorIndexingService.saveChunks(
                    document.getKnowledgeBaseId(), documentId, userId, chunks, embeddings, embeddingModelVersion);

            // 处理成功：回写元信息并推进状态机到 INDEXED
            document.setPageCount(parsingResult.locationCount());
            document.setTokenCount(totalTokens);
            document.setEmbeddingModelVersion(embeddingModelVersion);
            document.setStatus(KnowledgeDocument.STATUS_INDEXED);
            document.setErrorMessage(null);
            knowledgeDocumentRepository.updateById(document);
            knowledgeBaseService.refreshDerivedStatus(document.getKnowledgeBaseId());
            log.info("知识库文档索引成功 documentId={} knowledgeBaseId={} status=UPLOADED→INDEXED chunks={} tokens={} pages={} costMs={}",
                    documentId, document.getKnowledgeBaseId(), chunks.size(), totalTokens,
                    parsingResult.locationCount(), (System.nanoTime() - startNanos) / 1_000_000);
        } catch (Exception exception) {
            // 统一进入失败处理（含重试逻辑）
            log.error("知识库文档处理异常 documentId={} filename={} attempt={} costMs={}",
                    documentId, document.getFilename(), document.getRetryCount(),
                    (System.nanoTime() - startNanos) / 1_000_000, exception);
            handleFailure(document, exception);
        }
    }

    /**
     * 失败处理与重试。
     *
     * <p>为什么：首次失败（retryCount &lt; 1）先清理可能已写入的向量，回退状态到
     * UPLOADED 再立即重试一次，提升偶发错误（如模型超时）的成功率；
     * 重试仍失败则置为 FAILED 并保留错误信息供前端展示。
     * 每次状态变化都刷新知识库派生状态。
     */
    private void handleFailure(KnowledgeDocument document, Exception exception) {
        if (document.getRetryCount() < 1) {
            // 清理本次可能已写入的部分向量，避免脏数据
            knowledgeVectorIndexingService.deleteByDocumentId(document.getId());
            document.setRetryCount(document.getRetryCount() + 1);
            // 回退状态，等待立即重试
            document.setStatus(KnowledgeDocument.STATUS_UPLOADED);
            document.setErrorMessage(exception.getMessage());
            knowledgeDocumentRepository.updateById(document);
            knowledgeBaseService.refreshDerivedStatus(document.getKnowledgeBaseId());
            log.warn("知识库文档处理失败，自动重试 documentId={} status=PROCESSING→UPLOADED reason={}",
                    document.getId(), exception.getMessage());
            // 立即重试一次
            processInternal(document.getId(), document.getUserId());
        } else {
            // 已达重试上限，标记为最终失败
            document.setStatus(KnowledgeDocument.STATUS_FAILED);
            document.setErrorMessage(exception.getMessage());
            knowledgeDocumentRepository.updateById(document);
            knowledgeBaseService.refreshDerivedStatus(document.getKnowledgeBaseId());
            log.error("知识库文档索引最终失败 documentId={} knowledgeBaseId={} status=→FAILED reason={}",
                    document.getId(), document.getKnowledgeBaseId(), exception.getMessage());
        }
    }

    /**
     * 定时恢复卡死任务：回收 PROCESSING 状态停留超过 run-timeout-ms 的知识库文档。
     * 行为对齐现有 recoverStuckJobs：
     *  - 已达重试上限（retryCount &gt;= 1）的卡死文档直接置为 FAILED；
     *  - 未达上限的先消耗一次重试机会（retryCount+1）、复位为 UPLOADED 后立即重新处理。
     */
    @Override
    @Scheduled(fixedDelayString = "${wisread.document.recovery-interval-ms:30000}")
    public void recoverStuckJobs() {
        Instant threshold = Instant.now().minus(Duration.ofMillis(runTimeoutMs));
        for (KnowledgeDocument document : knowledgeDocumentRepository
                .findByStatusAndUpdatedAtBefore(KnowledgeDocument.STATUS_PROCESSING, threshold)) {
            if (document.getRetryCount() != null && document.getRetryCount() >= MAX_ATTEMPTS - 1) {
                // 已重试过仍卡死，标记为最终失败
                document.setStatus(KnowledgeDocument.STATUS_FAILED);
                document.setErrorMessage("文档处理超时，已达到最大重试次数");
                knowledgeDocumentRepository.updateById(document);
                knowledgeBaseService.refreshDerivedStatus(document.getKnowledgeBaseId());
                log.warn("知识库文档卡死恢复：已达重试上限，标记失败 documentId={} status=PROCESSING→FAILED",
                        document.getId());
                continue;
            }
            // 消耗一次重试机会并复位为 UPLOADED（CAS 抢占要求 UPLOADED 起点），随后立即重新处理
            document.setRetryCount((document.getRetryCount() == null ? 0 : document.getRetryCount()) + 1);
            document.setStatus(KnowledgeDocument.STATUS_UPLOADED);
            document.setErrorMessage("文档处理超时，自动重试");
            knowledgeDocumentRepository.updateById(document);
            knowledgeBaseService.refreshDerivedStatus(document.getKnowledgeBaseId());
            log.warn("知识库文档卡死恢复：超时自动重试 documentId={} status=PROCESSING→UPLOADED",
                    document.getId());
            processInternal(document.getId(), document.getUserId());
        }
    }
}
