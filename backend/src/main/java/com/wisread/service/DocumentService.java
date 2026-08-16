package com.wisread.service;

import com.wisread.dto.DocumentResponse;
import com.wisread.entity.Document;
import com.wisread.entity.DocumentJob;
import com.wisread.exception.ApiException;
import com.wisread.repository.DocumentJobRepository;
import com.wisread.repository.DocumentRepository;
import com.wisread.repository.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Service
public class DocumentService {

    private static final long MAX_FILE_SIZE = 100L * 1024 * 1024;
    private static final int MAX_DOCUMENTS_PER_PROJECT = 5;

    private final DocumentRepository documentRepository;
    private final DocumentJobRepository documentJobRepository;
    private final ProjectRepository projectRepository;
    private final MinioStorageService minioStorageService;
    private final DocumentProcessingService documentProcessingService;

    public DocumentService(
            DocumentRepository documentRepository,
            DocumentJobRepository documentJobRepository,
            ProjectRepository projectRepository,
            MinioStorageService minioStorageService,
            DocumentProcessingService documentProcessingService
    ) {
        this.documentRepository = documentRepository;
        this.documentJobRepository = documentJobRepository;
        this.projectRepository = projectRepository;
        this.minioStorageService = minioStorageService;
        this.documentProcessingService = documentProcessingService;
    }

    @Transactional
    public DocumentResponse upload(Long userId, Long projectId, MultipartFile file) {
        if (projectId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "projectId is required");
        }
        projectRepository.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));

        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "file is required");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "file exceeds 100MB limit");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "failed to read uploaded file");
        }
        if (!isPdf(bytes)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "only PDF files are supported");
        }
        if (documentRepository.countByProjectId(projectId) >= MAX_DOCUMENTS_PER_PROJECT) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "project document limit of " + MAX_DOCUMENTS_PER_PROJECT + " reached");
        }

        String fileKey = userId + "/" + UUID.randomUUID() + ".pdf";
        minioStorageService.putObject(fileKey, bytes, "application/pdf");

        Document document = new Document();
        document.setUserId(userId);
        document.setProjectId(projectId);
        document.setFilename(file.getOriginalFilename());
        document.setFileKey(fileKey);
        document.setFileSize((long) bytes.length);
        document.setStatus("UPLOADED");
        documentRepository.save(document);

        DocumentJob job = new DocumentJob();
        job.setDocumentId(document.getId());
        job.setStatus("PENDING");
        documentJobRepository.save(job);

        documentProcessingService.processDocument(document.getId(), userId);
        return toResponse(document);
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> listByProject(Long userId, Long projectId) {
        projectRepository.findByUserIdAndId(userId, projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "project not found"));
        return documentRepository.findByProjectIdOrderByCreatedAtDesc(projectId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<DocumentResponse> list(Long userId) {
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public DocumentResponse get(Long userId, Long documentId) {
        return toResponse(findOwnedDocument(userId, documentId));
    }

    @Transactional
    public void delete(Long userId, Long documentId) {
        Document document = findOwnedDocument(userId, documentId);
        minioStorageService.deleteObject(document.getFileKey());
        documentRepository.delete(document);
    }

    private Document findOwnedDocument(Long userId, Long documentId) {
        return documentRepository.findByUserIdAndId(userId, documentId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document not found"));
    }

    private boolean isPdf(byte[] bytes) {
        return bytes.length >= 4
                && bytes[0] == '%'
                && bytes[1] == 'P'
                && bytes[2] == 'D'
                && bytes[3] == 'F';
    }

    private DocumentResponse toResponse(Document document) {
        return new DocumentResponse(
                document.getId(),
                document.getProjectId(),
                document.getFilename(),
                document.getFileSize(),
                document.getPageCount(),
                document.getTokenCount(),
                document.getStatus(),
                document.getErrorMessage(),
                document.getCreatedAt(),
                document.getUpdatedAt()
        );
    }
}
