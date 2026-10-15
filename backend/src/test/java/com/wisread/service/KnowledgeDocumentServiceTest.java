package com.wisread.service;

import com.wisread.dto.KnowledgeChunkPageResponse;
import com.wisread.dto.KnowledgeDocumentBatchItemResponse;
import com.wisread.entity.KnowledgeBase;
import com.wisread.entity.KnowledgeDocument;
import com.wisread.exception.ApiException;
import com.wisread.model.KnowledgeChunkView;
import com.wisread.repository.KnowledgeDocumentRepository;
import com.wisread.service.impl.KnowledgeDocumentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 知识库文档服务（KnowledgeDocumentServiceImpl）的 Mockito 单元测试。
 * 不启动 Spring 上下文，直接构造实现类并 mock 全部依赖，
 * 覆盖上传校验、归档拦截、越权拦截、批量上传隔离、重新解析幂等与切片分页。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeDocumentServiceTest {

    @Mock
    private KnowledgeBaseService knowledgeBaseService;

    @Mock
    private KnowledgeDocumentRepository knowledgeDocumentRepository;

    @Mock
    private KnowledgeVectorIndexingService knowledgeVectorIndexingService;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private KnowledgeDocumentProcessingService knowledgeDocumentProcessingService;

    private KnowledgeDocumentService knowledgeDocumentService;

    private static final Long USER_ID = 1L;
    private static final Long KB_ID = 7L;

    @BeforeEach
    void setUp() {
        knowledgeDocumentService = new KnowledgeDocumentServiceImpl(
                knowledgeBaseService,
                knowledgeDocumentRepository,
                knowledgeVectorIndexingService,
                minioStorageService,
                knowledgeDocumentProcessingService
        );
    }

    // 构造指定 id 与状态的知识库实体
    private KnowledgeBase kb(String status) {
        KnowledgeBase knowledgeBase = new KnowledgeBase();
        ReflectionTestUtils.setField(knowledgeBase, "id", KB_ID);
        knowledgeBase.setUserId(USER_ID);
        knowledgeBase.setStatus(status);
        return knowledgeBase;
    }

    // 构造归属当前用户的知识库文档实体
    private KnowledgeDocument document(String status, int retryCount) {
        KnowledgeDocument document = new KnowledgeDocument();
        ReflectionTestUtils.setField(document, "id", 10L);
        document.setKnowledgeBaseId(KB_ID);
        document.setUserId(USER_ID);
        document.setFileKey("knowledge/1/7/test.txt");
        document.setFileType("txt");
        document.setStatus(status);
        document.setRetryCount(retryCount);
        return document;
    }

    // mock 当前用户拥有 DRAFT 知识库
    private void mockOwnedDraft() {
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID)).thenReturn(kb(KnowledgeBase.STATUS_DRAFT));
    }

    @Test
    void uploadSucceedsAndTriggersAsyncProcessing() {
        mockOwnedDraft();
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "智阅知识库文档".getBytes());

        KnowledgeDocument uploaded = knowledgeDocumentService.upload(USER_ID, KB_ID, file);

        assertThat(uploaded.getStatus()).isEqualTo(KnowledgeDocument.STATUS_UPLOADED);
        assertThat(uploaded.getFileType()).isEqualTo("txt");
        assertThat(uploaded.getKnowledgeBaseId()).isEqualTo(KB_ID);
        ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(knowledgeDocumentRepository).insert(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(KnowledgeDocument.STATUS_UPLOADED);
        // MinIO 对象键使用 knowledge/ 前缀并包含 userId/kbId 防重名
        ArgumentCaptor<String> fileKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(minioStorageService).putObject(fileKeyCaptor.capture(), any(byte[].class), eq("text/plain"));
        assertThat(fileKeyCaptor.getValue()).startsWith("knowledge/1/7/");
        // 插入后刷新知识库派生状态，并触发异步处理
        verify(knowledgeBaseService).refreshDerivedStatus(KB_ID);
        verify(knowledgeDocumentProcessingService).processDocument(isNull(), eq(USER_ID));
    }

    @Test
    void uploadRejectsArchivedKnowledgeBase() {
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenReturn(kb(KnowledgeBase.STATUS_ARCHIVED));
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "内容".getBytes());

        assertThatThrownBy(() -> knowledgeDocumentService.upload(USER_ID, KB_ID, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("知识库已归档，禁止上传文档")
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        verify(minioStorageService, never()).putObject(any(), any(byte[].class), any());
        verify(knowledgeDocumentRepository, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void uploadRejectsWhenNotOwner() {
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "无权访问该知识库"));
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain", "内容".getBytes());

        assertThatThrownBy(() -> knowledgeDocumentService.upload(USER_ID, KB_ID, file))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(knowledgeDocumentRepository, never()).insert(any(KnowledgeDocument.class));
    }

    @Test
    void uploadRejectsUnsupportedFileType() {
        mockOwnedDraft();
        MockMultipartFile file = new MockMultipartFile(
                "file", "archive.zip", "application/zip", "hello".getBytes());

        assertThatThrownBy(() -> knowledgeDocumentService.upload(USER_ID, KB_ID, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("不支持的文档类型")
                .extracting("status")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void uploadRejectsMismatchedDocxMagic() {
        mockOwnedDraft();
        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "not a real docx".getBytes());

        assertThatThrownBy(() -> knowledgeDocumentService.upload(USER_ID, KB_ID, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("扩展名不符")
                .extracting("status")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void uploadRejectsOversizeFile() {
        mockOwnedDraft();
        MockMultipartFile file = new MockMultipartFile(
                "file", "big.pdf", "application/pdf", new byte[100 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> knowledgeDocumentService.upload(USER_ID, KB_ID, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("100MB")
                .extracting("status")
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void batchUploadIsolatesPerFileFailures() {
        mockOwnedDraft();
        MockMultipartFile okFile = new MockMultipartFile(
                "files", "ok.txt", "text/plain", "合法内容".getBytes());
        MockMultipartFile badFile = new MockMultipartFile(
                "files", "bad.zip", "application/zip", "非法类型".getBytes());

        List<KnowledgeDocumentBatchItemResponse> results =
                knowledgeDocumentService.batchUpload(USER_ID, KB_ID, List.of(okFile, badFile));

        assertThat(results).hasSize(2);
        assertThat(results.get(0).success()).isTrue();
        assertThat(results.get(0).document()).isNotNull();
        assertThat(results.get(1).success()).isFalse();
        assertThat(results.get(1).errorMessage()).contains("不支持的文档类型");
        // 单文件失败不影响其他文件：合法文件仍完成落库与异步触发
        verify(knowledgeDocumentRepository, times(1)).insert(any(KnowledgeDocument.class));
        verify(knowledgeDocumentProcessingService, times(1)).processDocument(any(), eq(USER_ID));
    }

    @Test
    void getRejectsForeignDocument() {
        when(knowledgeDocumentRepository.findByUserIdAndId(USER_ID, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> knowledgeDocumentService.get(USER_ID, 10L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("无权访问该知识库")
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void reparseRejectsWhileProcessing() {
        when(knowledgeDocumentRepository.findByUserIdAndId(USER_ID, 10L))
                .thenReturn(Optional.of(document(KnowledgeDocument.STATUS_PROCESSING, 0)));

        assertThatThrownBy(() -> knowledgeDocumentService.reparse(USER_ID, 10L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("文档处理中，请勿重复操作")
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        verify(knowledgeVectorIndexingService, never()).deleteByDocumentId(anyLong());
        verify(knowledgeDocumentProcessingService, never()).processDocument(any(), any());
    }

    @Test
    void reparseResetsAndRetriggers() {
        when(knowledgeDocumentRepository.findByUserIdAndId(USER_ID, 10L))
                .thenReturn(Optional.of(document(KnowledgeDocument.STATUS_FAILED, 1)));
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenReturn(kb(KnowledgeBase.STATUS_ACTIVE));

        knowledgeDocumentService.reparse(USER_ID, 10L);

        verify(knowledgeVectorIndexingService).deleteByDocumentId(10L);
        ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(knowledgeDocumentRepository).updateById(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(KnowledgeDocument.STATUS_UPLOADED);
        assertThat(captor.getValue().getRetryCount()).isZero();
        assertThat(captor.getValue().getErrorMessage()).isNull();
        verify(knowledgeBaseService).refreshDerivedStatus(KB_ID);
        verify(knowledgeDocumentProcessingService).processDocument(eq(10L), eq(USER_ID));
    }

    @Test
    void reparseRejectsArchivedKnowledgeBase() {
        when(knowledgeDocumentRepository.findByUserIdAndId(USER_ID, 10L))
                .thenReturn(Optional.of(document(KnowledgeDocument.STATUS_INDEXED, 0)));
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenReturn(kb(KnowledgeBase.STATUS_ARCHIVED));

        assertThatThrownBy(() -> knowledgeDocumentService.reparse(USER_ID, 10L))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("知识库已归档")
                .extracting("status")
                .isEqualTo(HttpStatus.CONFLICT);
        verify(knowledgeDocumentProcessingService, never()).processDocument(any(), any());
    }

    @Test
    void deleteCleansVectorMinioAndRecord() {
        when(knowledgeDocumentRepository.findByUserIdAndId(USER_ID, 10L))
                .thenReturn(Optional.of(document(KnowledgeDocument.STATUS_INDEXED, 0)));

        knowledgeDocumentService.delete(USER_ID, 10L);

        verify(knowledgeVectorIndexingService).deleteByDocumentId(10L);
        verify(minioStorageService).deleteObject("knowledge/1/7/test.txt");
        verify(knowledgeDocumentRepository).deleteById(10L);
        verify(knowledgeBaseService).refreshDerivedStatus(KB_ID);
    }

    @Test
    void pageChunksReturnsPagedItems() {
        when(knowledgeDocumentRepository.findByUserIdAndId(USER_ID, 10L))
                .thenReturn(Optional.of(document(KnowledgeDocument.STATUS_INDEXED, 0)));
        when(knowledgeVectorIndexingService.countByDocumentId(10L)).thenReturn(3L);
        when(knowledgeVectorIndexingService.listChunksByDocumentId(10L, 0, 20)).thenReturn(List.of(
                new KnowledgeChunkView(0, "第一块", 1, 1, "页", 5),
                new KnowledgeChunkView(1, "第二块", 1, 2, "页", 8)
        ));

        KnowledgeChunkPageResponse response = knowledgeDocumentService.pageChunks(USER_ID, 10L, 1, 20);

        assertThat(response.total()).isEqualTo(3L);
        assertThat(response.page()).isEqualTo(1);
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).content()).isEqualTo("第一块");
        assertThat(response.items().get(1).chunkIndex()).isEqualTo(1);
    }

    @Test
    void pageChunksRejectsForeignDocument() {
        when(knowledgeDocumentRepository.findByUserIdAndId(USER_ID, 10L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> knowledgeDocumentService.pageChunks(USER_ID, 10L, 1, 20))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(knowledgeVectorIndexingService, never()).listChunksByDocumentId(anyLong(), anyInt(), anyInt());
    }

    @Test
    void listRejectsForeignKnowledgeBase() {
        when(knowledgeBaseService.findOwnedKnowledgeBase(USER_ID, KB_ID))
                .thenThrow(new ApiException(HttpStatus.FORBIDDEN, "无权访问该知识库"));

        assertThatThrownBy(() -> knowledgeDocumentService.listByKnowledgeBase(USER_ID, KB_ID))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.FORBIDDEN);
        verify(knowledgeDocumentRepository, never()).findByKnowledgeBaseIdOrderByCreatedAtDesc(anyLong());
    }
}
