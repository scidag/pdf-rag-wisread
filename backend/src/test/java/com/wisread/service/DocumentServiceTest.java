package com.wisread.service;

import com.wisread.entity.Document;
import com.wisread.exception.ApiException;
import com.wisread.repository.DocumentJobRepository;
import com.wisread.repository.DocumentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DocumentServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private DocumentJobRepository documentJobRepository;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private DocumentProcessingService documentProcessingService;

    private DocumentService documentService;

    @BeforeEach
    void setUp() {
        documentService = new DocumentService(
                documentRepository,
                documentJobRepository,
                minioStorageService,
                documentProcessingService
        );
    }

    @Test
    void uploadRejectsNonPdf() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes()
        );

        assertThatThrownBy(() -> documentService.upload(1L, file))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void uploadRejectsOversizeFile() {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "big.pdf",
                "application/pdf",
                new byte[20 * 1024 * 1024 + 1]
        );

        assertThatThrownBy(() -> documentService.upload(1L, file))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void uploadRejectsWhenUserLimitReached() {
        when(documentRepository.countByUserId(1L)).thenReturn(5L);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "doc.pdf",
                "application/pdf",
                "%PDF-1.7 test".getBytes()
        );

        assertThatThrownBy(() -> documentService.upload(1L, file))
                .isInstanceOf(ApiException.class)
                .hasMessage("user document limit of 5 reached");
    }

    @Test
    void deleteOnlyRemovesOwnedDocument() {
        when(documentRepository.findByUserIdAndId(1L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.delete(1L, 99L))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void deleteOwnedDocumentRemovesMinioObject() {
        Document document = new Document();
        document.setUserId(1L);
        document.setFileKey("1/abc.pdf");
        ReflectionTestUtils.setField(document, "id", 10L);
        when(documentRepository.findByUserIdAndId(1L, 10L)).thenReturn(Optional.of(document));

        documentService.delete(1L, 10L);

        verify(minioStorageService).deleteObject("1/abc.pdf");
        verify(documentRepository).delete(any(Document.class));
    }
}
