package com.wisread.controller;

import com.wisread.dto.DocumentResponse;
import com.wisread.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("projectId") Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(documentService.upload(userId, projectId, file));
    }

    @GetMapping
    public ResponseEntity<List<DocumentResponse>> list(
            @RequestParam("projectId") Long projectId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(documentService.listByProject(userId, projectId));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> get(
            @PathVariable Long documentId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(documentService.get(userId, documentId));
    }

    @GetMapping("/{documentId}/status")
    public ResponseEntity<DocumentResponse> status(
            @PathVariable Long documentId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        return ResponseEntity.ok(documentService.get(userId, documentId));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(
            @PathVariable Long documentId,
            Authentication authentication
    ) {
        Long userId = (Long) authentication.getPrincipal();
        documentService.delete(userId, documentId);
        return ResponseEntity.noContent().build();
    }
}
