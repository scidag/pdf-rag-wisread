package com.wisread.service.impl;

import com.wisread.service.DocumentProcessingService;
import com.wisread.service.ChunkingService;
import com.wisread.service.EmbeddingService;
import com.wisread.service.MinioStorageService;
import com.wisread.service.PdfParsingService;
import com.wisread.service.TokenCounter;
import com.wisread.service.VectorIndexingService;

import com.wisread.entity.Document;
import com.wisread.entity.DocumentJob;
import com.wisread.exception.ApiException;
import com.wisread.model.PageText;
import com.wisread.model.TextChunk;
import com.wisread.repository.DocumentJobRepository;
import com.wisread.repository.DocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 文档处理异步流水线实现（DocumentProcessingServiceImpl）。
 *
 * <p>实现要点（@Async 后台执行，端到端数据流）：
 * <ol>
 *   <li>状态置为 PROCESSING/RUNNING，记录开始时间。</li>
 *   <li>从 MinIO 取回 PDF 字节，逐页抽取文本（PdfParsingService）。</li>
 *   <li>逐页分块（ChunkingService），累加 token 数并做全局上限校验（50 万）。</li>
 *   <li>对全部文本做 embedding，调用 VectorIndexingService 写入 pgvector。</li>
 *   <li>成功后状态机推进到 READY/SUCCEEDED，回写页数、token 数、embedding 模型版本。</li>
 * </ol>
 *
 * <p>失败重试：{@code handleFailure} 在 {@code retryCount < 1} 时清理已写向量、回退状态并重新处理一次；
 * 仍失败则置为 FAILED 并保留错误信息。空/扫描件 PDF 直接以 422 失败。
 */
@Service
public class DocumentProcessingServiceImpl implements DocumentProcessingService {

    // 单文档 token 总量上限（50 万），超出视为不合规文档，直接失败
    private static final int MAX_TOKEN_COUNT = 500_000;
    // 处理总尝试次数上限（首次 + 1 次重试）
    private static final int MAX_ATTEMPTS = 2;

    private final DocumentRepository documentRepository;
    private final DocumentJobRepository documentJobRepository;
    private final PdfParsingService pdfParsingService;
    private final ChunkingService chunkingService;
    private final TokenCounter tokenCounter;
    private final EmbeddingService embeddingService;
    private final VectorIndexingService vectorIndexingService;
    private final MinioStorageService minioStorageService;
    private final String embeddingModelVersion;

    @org.springframework.beans.factory.annotation.Value("${wisread.document.run-timeout-ms:600000}")
    private long runTimeoutMs;

    public DocumentProcessingServiceImpl(
            DocumentRepository documentRepository,
            DocumentJobRepository documentJobRepository,
            PdfParsingService pdfParsingService,
            ChunkingService chunkingService,
            TokenCounter tokenCounter,
            EmbeddingService embeddingService,
            VectorIndexingService vectorIndexingService,
            MinioStorageService minioStorageService,
            @Value("${spring.ai.openai.embedding.options.model:qwen3.7-text-embedding}") String embeddingModelVersion
    ) {
        this.documentRepository = documentRepository;
        this.documentJobRepository = documentJobRepository;
        this.pdfParsingService = pdfParsingService;
        this.chunkingService = chunkingService;
        this.tokenCounter = tokenCounter;
        this.embeddingService = embeddingService;
        this.vectorIndexingService = vectorIndexingService;
        this.minioStorageService = minioStorageService;
        this.embeddingModelVersion = embeddingModelVersion;
    }

    /**
     * 异步处理文档入口。
     *
     * <p>标注 {@code @Async("documentTaskExecutor")}，调用方（上传接口）不会阻塞，
     * 真正的流水线在 {@code documentTaskExecutor} 线程池中执行。
     */
    @Async("documentTaskExecutor")
    public void processDocument(Long documentId, Long userId) {
        processInternal(documentId, userId);
    }

    /**
     * 处理流水线核心。
     *
     * <p>依次完成：状态推进 → MinIO 取 PDF → 逐页抽取 → 逐页分块并累计 token →
     * token 上限校验 → 批量 embedding → 写入 pgvector → 状态推进到 READY。
     */
    private void processInternal(Long documentId, Long userId) {
        Document document = documentRepository.findByUserIdAndId(userId, documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document not found"));
        DocumentJob job = documentJobRepository.findByDocumentId(documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document job not found"));

        // 幂等：已完成任务直接跳过，避免重复向量化
        if ("READY".equals(document.getStatus()) && "SUCCEEDED".equals(job.getStatus())) {
            return;
        }
        // 原子抢占 PENDING -> RUNNING，避免并发重复处理
        if (documentJobRepository.claimPending(job.getId()) == 0) {
            return;
        }
        job.setStatus("RUNNING");
        job.setStartedAt(Instant.now());
        job.setErrorMessage(null);
        document.setStatus("PROCESSING");
        document.setErrorMessage(null);
        documentRepository.updateById(document);
        documentJobRepository.updateById(job);

        try {
            // 幂等：重新处理前清理旧向量，避免部分失败后残留脏数据
            vectorIndexingService.deleteByDocumentId(documentId);
            // 从对象存储取回 PDF 原始字节
            byte[] pdfBytes = minioStorageService.getObject(document.getFileKey());
            // 逐页抽取文本
            List<PageText> pages = pdfParsingService.extractPages(pdfBytes);
            // 所有页均为空文本，视为扫描件/空 PDF，直接失败
            if (pages.stream().allMatch(page -> page.text().isBlank())) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "scanned or empty PDF is not supported");
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
            // 全局 token 上限校验，超出直接失败避免后续成本失控
            if (totalTokens > MAX_TOKEN_COUNT) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "document exceeds 500000 token limit");
            }

            // 批量向量化并写入 pgvector
            List<String> texts = chunks.stream().map(TextChunk::content).toList();
            List<float[]> embeddings = embeddingService.embed(texts, userId);
            vectorIndexingService.saveChunks(documentId, userId, chunks, embeddings, embeddingModelVersion);

            // 处理成功：回写元信息并推进状态机到 READY/SUCCEEDED
            document.setPageCount(pages.size());
            document.setTokenCount(totalTokens);
            document.setEmbeddingModelVersion(embeddingModelVersion);
            document.setStatus("READY");
            document.setErrorMessage(null);
            job.setStatus("SUCCEEDED");
            job.setFinishedAt(Instant.now());
            job.setErrorMessage(null);
            documentRepository.updateById(document);
            documentJobRepository.updateById(job);
        } catch (Exception exception) {
            // 统一进入失败处理（含重试逻辑）
            handleFailure(document, job, exception);
        }
    }

    /**
     * 失败处理与重试。
     *
     * <p>为什么：首次失败（retryCount < 1）先清理可能已写入的向量，回退状态到
     * UPLOADED/PENDING 再自动重试一次，提升偶发错误（如模型超时）的成功率；
     * 重试仍失败则置为 FAILED 并保留错误信息供前端展示。
     */
    @Transactional
    public void handleFailure(Document document, DocumentJob job, Exception exception) {
        if (document.getRetryCount() < 1) {
            // 清理上次可能已写入的部分向量，避免脏数据
            vectorIndexingService.deleteByDocumentId(document.getId());
            document.setRetryCount(document.getRetryCount() + 1);
            // 回退状态，等待下一次调度重试
            document.setStatus("UPLOADED");
            document.setErrorMessage(exception.getMessage());
            job.setAttempt(job.getAttempt() + 1);
            job.setStatus("PENDING");
            job.setStartedAt(null);
            job.setFinishedAt(null);
            job.setErrorMessage(exception.getMessage());
            documentRepository.updateById(document);
            documentJobRepository.updateById(job);
            // 立即重试一次
            processInternal(document.getId(), document.getUserId());
        } else {
            // 已达重试上限，标记为最终失败
            document.setStatus("FAILED");
            document.setErrorMessage(exception.getMessage());
            job.setStatus("FAILED");
            job.setFinishedAt(Instant.now());
            job.setErrorMessage(exception.getMessage());
            documentRepository.updateById(document);
            documentJobRepository.updateById(job);
        }
    }

    /**
     * 定时恢复卡死任务：回收长时间未开始的 PENDING，以及执行超时的 RUNNING。
     * ponytail: 顺序处理少量卡死任务，任务量上来后改独立调度线程/队列。
     */
    @Scheduled(fixedDelayString = "${wisread.document.recovery-interval-ms:30000}")
    public void recoverStuckJobs() {
        Instant now = Instant.now();
        for (DocumentJob job : documentJobRepository.findPendingOlderThan(now.minus(Duration.ofSeconds(30)))) {
            if (job.getAttempt() != null && job.getAttempt() >= MAX_ATTEMPTS) {
                job.setStatus("FAILED");
                job.setFinishedAt(Instant.now());
                job.setErrorMessage("max attempts reached");
                documentJobRepository.updateById(job);
                documentRepository.findById(job.getDocumentId()).ifPresent(document -> {
                    document.setStatus("FAILED");
                    document.setErrorMessage("max attempts reached");
                    documentRepository.updateById(document);
                });
                continue;
            }
            documentRepository.findById(job.getDocumentId())
                    .ifPresent(document -> processInternal(document.getId(), document.getUserId()));
        }
        for (DocumentJob job : documentJobRepository.findRunningOlderThan(now.minus(Duration.ofMillis(runTimeoutMs)))) {
            job.setStatus("PENDING");
            job.setAttempt((job.getAttempt() == null ? 0 : job.getAttempt()) + 1);
            job.setStartedAt(null);
            job.setErrorMessage("processing timeout, scheduled retry");
            documentJobRepository.updateById(job);
            documentRepository.findById(job.getDocumentId())
                    .ifPresent(document -> processInternal(document.getId(), document.getUserId()));
        }
    }
}
