package com.wisread.service;

import com.wisread.entity.Document;
import com.wisread.entity.Project;
import com.wisread.exception.ApiException;
import com.wisread.repository.DocumentJobRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.ProjectRepository;
import com.wisread.service.impl.DocumentServiceImpl;
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
    private ProjectRepository projectRepository;

    @Mock
    private MinioStorageService minioStorageService;

    @Mock
    private DocumentProcessingService documentProcessingService;

    private DocumentService documentService;

    private static final Long USER_ID = 1L;
    private static final Long PROJECT_ID = 7L;

    @BeforeEach
    void setUp() {
        documentService = new DocumentServiceImpl(
                documentRepository,
                documentJobRepository,
                projectRepository,
                minioStorageService,
                documentProcessingService
        );
    }

    private void mockProjectOwned() {
        Project project = new Project();
        ReflectionTestUtils.setField(project, "id", PROJECT_ID);
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, PROJECT_ID))
                .thenReturn(Optional.of(project));
    }

    @Test
    void uploadRejectsNonPdf() {
        mockProjectOwned();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "notes.txt",
                "text/plain",
                "hello".getBytes()
        );

        assertThatThrownBy(() -> documentService.upload(USER_ID, PROJECT_ID, file))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void uploadRejectsOversizeFile() {
        mockProjectOwned();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "big.pdf",
                "application/pdf",
                new byte[100 * 1024 * 1024 + 1]
        );

        assertThatThrownBy(() -> documentService.upload(USER_ID, PROJECT_ID, file))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
    }

    @Test
    void uploadRejectsWhenProjectLimitReached() {
        mockProjectOwned();
        when(documentRepository.countByProjectId(PROJECT_ID)).thenReturn(5L);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "doc.pdf",
                "application/pdf",
                "%PDF-1.7 test".getBytes()
        );

        assertThatThrownBy(() -> documentService.upload(USER_ID, PROJECT_ID, file))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("project document limit");
    }

    @Test
    void uploadRejectsWhenProjectNotFound() {
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "doc.pdf",
                "application/pdf",
                "%PDF-1.7 test".getBytes()
        );

        assertThatThrownBy(() -> documentService.upload(USER_ID, PROJECT_ID, file))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getRejectsDocumentInDeletedProject() {
        Document document = new Document();
        document.setUserId(USER_ID);
        document.setProjectId(PROJECT_ID);
        ReflectionTestUtils.setField(document, "id", 10L);
        when(documentRepository.findByUserIdAndId(USER_ID, 10L))
                .thenReturn(Optional.of(document));
        when(projectRepository.findByUserIdAndIdAndDeletedAtIsNull(USER_ID, PROJECT_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> documentService.get(USER_ID, 10L))
                .isInstanceOf(ApiException.class)
                .extracting("status")
                .isEqualTo(HttpStatus.NOT_FOUND);
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
        verify(documentRepository).deleteById(10L);
    }
}
