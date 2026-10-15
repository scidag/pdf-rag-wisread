package com.wisread.service;

import com.wisread.entity.KnowledgeDocument;
import com.wisread.model.DocumentParsingResult;
import com.wisread.model.PageText;
import com.wisread.model.TextChunk;
import com.wisread.repository.KnowledgeDocumentRepository;
import com.wisread.service.impl.KnowledgeDocumentProcessingServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库文档处理流水线（KnowledgeDocumentProcessingServiceImpl）的 Mockito 单元测试。
 * 不启动 Spring 上下文（@Async/@Scheduled 注解在纯单测中不生效，方法同步执行），
 * 覆盖：成功路径状态推进与派生状态刷新、CAS 抢占失败跳过、失败自动重试 1 次后 FAILED、
 * token 超限 422、扫描件 422、定时恢复卡死任务。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentProcessingServiceTest {

    @Mock
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    private KnowledgeVectorIndexingService knowledgeVectorIndexingService;

    @Mock
    private DocumentParsingService documentParsingService;

    @Mock
    private ChunkingService chunkingService;

    @Mock
    private EmbeddingService embeddingService;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    private KnowledgeDocumentProcessingServiceImpl service;

    private static final Long USER_ID = 1L;
    private static final Long KB_ID = 7L;
    private static final Long DOC_ID = 10L;

    @BeforeEach
    void setUp() {
        service = new KnowledgeDocumentProcessingServiceImpl(
                knowledgeDocumentRepository,
                knowledgeVectorIndexingService,
                documentParsingService,
                chunkingService,
                embeddingService,
                minioStorageService,
                knowledgeBaseService,
                "test-embedding"
        );
        ReflectionTestUtils.setField(service, "runTimeoutMs", 600_000L);
    }

    // 构造指定状态与重试次数的知识库文档实体
    private KnowledgeDocument document(String status, int retryCount) {
        KnowledgeDocument document = new KnowledgeDocument();
        ReflectionTestUtils.setField(document, "id", DOC_ID);
        document.setKnowledgeBaseId(KB_ID);
        document.setUserId(USER_ID);
        document.setFilename("test.txt");
        document.setFileKey("knowledge/1/7/test.txt");
        document.setFileType("txt");
        document.setStatus(status);
        document.setRetryCount(retryCount);
        return document;
    }

    // mock 一条可成功处理的管道：取文件 → 解析出 1 页 → 1 个 chunk(5 token) → 1 个向量
    private void mockHappyPipeline() {
        when(minioStorageService.getObject("knowledge/1/7/test.txt")).thenReturn("正文内容".getBytes());
        when(documentParsingService.parse(any(byte[].class), eq("txt")))
                .thenReturn(new DocumentParsingResult(List.of(new PageText(1, "正文内容")), 1));
        when(chunkingService.splitPage(any(PageText.class), eq(0)))
                .thenReturn(List.of(new TextChunk("正文内容", 1, 1, 5)));
        when(embeddingService.embed(anyList(), eq(USER_ID))).thenReturn(List.of(new float[]{0.1f, 0.2f}));
    }

    @Test
    void processDocumentCompletesSuccessfully() {
        KnowledgeDocument document = document(KnowledgeDocument.STATUS_UPLOADED, 0);
        when(knowledgeDocumentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(knowledgeDocumentRepository.claimProcessing(DOC_ID)).thenReturn(1);
        mockHappyPipeline();

        service.processDocument(DOC_ID, USER_ID);

        // 状态推进到 INDEXED 并回写解析元信息
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocument.STATUS_INDEXED);
        assertThat(document.getPageCount()).isEqualTo(1);
        assertThat(document.getTokenCount()).isEqualTo(5);
        assertThat(document.getErrorMessage()).isNull();
        assertThat(document.getEmbeddingModelVersion()).isEqualTo("test-embedding");
        // 切片连同知识库冗余列一起写入 knowledge_chunks
        verify(knowledgeVectorIndexingService).saveChunks(
                eq(KB_ID), eq(DOC_ID), eq(USER_ID), anyList(), anyList(), eq("test-embedding"));
        // 处理前幂等清理旧向量
        verify(knowledgeVectorIndexingService).deleteByDocumentId(DOC_ID);
        // 状态变化点（CAS 置 PROCESSING、成功置 INDEXED）各刷新一次派生状态
        verify(knowledgeBaseService, times(2)).refreshDerivedStatus(KB_ID);
        verify(knowledgeDocumentRepository, times(2)).updateById(document);
    }

    @Test
    void skipsWhenClaimFails() {
        when(knowledgeDocumentRepository.findById(DOC_ID)).thenReturn(Optional.of(document(
                KnowledgeDocument.STATUS_PROCESSING, 0)));
        when(knowledgeDocumentRepository.claimProcessing(DOC_ID)).thenReturn(0);

        service.processDocument(DOC_ID, USER_ID);

        // CAS 抢占失败说明他人已接管，直接跳过，不做任何处理
        verify(minioStorageService, never()).getObject(any());
        verify(knowledgeVectorIndexingService, never()).deleteByDocumentId(anyLong());
        verify(knowledgeBaseService, never()).refreshDerivedStatus(anyLong());
    }

    @Test
    void skipsWhenDocumentDeleted() {
        when(knowledgeDocumentRepository.findById(DOC_ID)).thenReturn(Optional.empty());

        service.processDocument(DOC_ID, USER_ID);

        verify(knowledgeDocumentRepository, never()).claimProcessing(anyLong());
        verify(minioStorageService, never()).getObject(any());
    }

    @Test
    void retriesOnceThenMarksFailed() {
        KnowledgeDocument document = document(KnowledgeDocument.STATUS_UPLOADED, 0);
        when(knowledgeDocumentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        // 首次与重试各抢占一次
        when(knowledgeDocumentRepository.claimProcessing(DOC_ID)).thenReturn(1, 1);
        when(minioStorageService.getObject("knowledge/1/7/test.txt")).thenReturn("正文内容".getBytes());
        when(documentParsingService.parse(any(byte[].class), eq("txt")))
                .thenReturn(new DocumentParsingResult(List.of(new PageText(1, "正文内容")), 1));
        when(chunkingService.splitPage(any(PageText.class), eq(0)))
                .thenReturn(List.of(new TextChunk("正文内容", 1, 1, 5)));
        // 两次 embedding 均失败，模拟持续故障
        when(embeddingService.embed(anyList(), eq(USER_ID)))
                .thenThrow(new RuntimeException("模型超时"))
                .thenThrow(new RuntimeException("模型超时"));

        service.processDocument(DOC_ID, USER_ID);

        // 首次失败回退 UPLOADED（retryCount+1），重试仍失败后置为 FAILED
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocument.STATUS_FAILED);
        assertThat(document.getRetryCount()).isEqualTo(1);
        assertThat(document.getErrorMessage()).isEqualTo("模型超时");
        // 每次失败清理已写入的部分向量（两次尝试 + 两次失败清理）
        verify(knowledgeVectorIndexingService, times(3)).deleteByDocumentId(DOC_ID);
        // 状态变化点共 4 次：两次 CAS、一次回退 UPLOADED、一次最终 FAILED
        verify(knowledgeBaseService, times(4)).refreshDerivedStatus(KB_ID);
    }

    @Test
    void failsWhenTokenLimitExceeded() {
        KnowledgeDocument document = document(KnowledgeDocument.STATUS_UPLOADED, 0);
        when(knowledgeDocumentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(knowledgeDocumentRepository.claimProcessing(DOC_ID)).thenReturn(1, 1);
        when(minioStorageService.getObject("knowledge/1/7/test.txt")).thenReturn("大量文本".getBytes());
        when(documentParsingService.parse(any(byte[].class), eq("txt")))
                .thenReturn(new DocumentParsingResult(List.of(new PageText(1, "大量文本")), 1));
        when(chunkingService.splitPage(any(PageText.class), eq(0)))
                .thenReturn(List.of(new TextChunk("大量文本", 1, 1, 600_000)));

        service.processDocument(DOC_ID, USER_ID);

        // token 超限 422 失败，重试仍超限后置 FAILED，且从未调用 embedding
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocument.STATUS_FAILED);
        assertThat(document.getErrorMessage()).contains("500000");
        verify(embeddingService, never()).embed(anyList(), anyLong());
        verify(knowledgeVectorIndexingService, never()).saveChunks(
                anyLong(), anyLong(), anyLong(), anyList(), anyList(), any());
    }

    @Test
    void failsWhenDocumentHasNoText() {
        KnowledgeDocument document = document(KnowledgeDocument.STATUS_UPLOADED, 0);
        when(knowledgeDocumentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(knowledgeDocumentRepository.claimProcessing(DOC_ID)).thenReturn(1, 1);
        when(minioStorageService.getObject("knowledge/1/7/test.txt")).thenReturn(new byte[0]);
        // 解析结果全部为空白文本：扫描件/空文档直接失败
        when(documentParsingService.parse(any(byte[].class), eq("txt")))
                .thenReturn(new DocumentParsingResult(List.of(new PageText(1, "   ")), 1));

        service.processDocument(DOC_ID, USER_ID);

        assertThat(document.getStatus()).isEqualTo(KnowledgeDocument.STATUS_FAILED);
        assertThat(document.getErrorMessage()).contains("扫描件");
        verify(chunkingService, never()).splitPage(any(PageText.class), anyInt());
    }

    @Test
    void recoverStuckJobsFailsStaleWhenRetryExhausted() {
        KnowledgeDocument document = document(KnowledgeDocument.STATUS_PROCESSING, 1);
        when(knowledgeDocumentRepository.findByStatusAndUpdatedAtBefore(
                eq(KnowledgeDocument.STATUS_PROCESSING), any(Instant.class)))
                .thenReturn(List.of(document));

        service.recoverStuckJobs();

        // 已达重试上限的卡死文档直接置为 FAILED，不再重新处理
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocument.STATUS_FAILED);
        assertThat(document.getErrorMessage()).contains("最大重试次数");
        verify(knowledgeDocumentRepository).updateById(document);
        verify(knowledgeBaseService).refreshDerivedStatus(KB_ID);
        verify(knowledgeDocumentRepository, never()).claimProcessing(anyLong());
    }

    @Test
    void recoverStuckJobsRetriesStaleDocument() {
        KnowledgeDocument document = document(KnowledgeDocument.STATUS_PROCESSING, 0);
        when(knowledgeDocumentRepository.findByStatusAndUpdatedAtBefore(
                eq(KnowledgeDocument.STATUS_PROCESSING), any(Instant.class)))
                .thenReturn(List.of(document));
        when(knowledgeDocumentRepository.findById(DOC_ID)).thenReturn(Optional.of(document));
        when(knowledgeDocumentRepository.claimProcessing(DOC_ID)).thenReturn(1);
        mockHappyPipeline();

        service.recoverStuckJobs();

        // 卡死恢复消耗一次重试机会（retryCount+1），重新处理后成功推进到 INDEXED
        assertThat(document.getRetryCount()).isEqualTo(1);
        assertThat(document.getStatus()).isEqualTo(KnowledgeDocument.STATUS_INDEXED);
        verify(knowledgeBaseService, times(3)).refreshDerivedStatus(KB_ID);
    }

    @Test
    void recoverStuckJobsDoesNothingWhenNoStaleDocuments() {
        when(knowledgeDocumentRepository.findByStatusAndUpdatedAtBefore(
                eq(KnowledgeDocument.STATUS_PROCESSING), any(Instant.class)))
                .thenReturn(List.of());

        service.recoverStuckJobs();

        verify(knowledgeDocumentRepository, never()).updateById(any(KnowledgeDocument.class));
        verify(knowledgeBaseService, never()).refreshDerivedStatus(anyLong());
    }
}
