package com.wisread.service;

import com.wisread.dto.DocumentResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface DocumentService {

    DocumentResponse upload(Long userId, Long projectId, MultipartFile file);

    List<DocumentResponse> listByProject(Long userId, Long projectId);

    List<DocumentResponse> list(Long userId);

    DocumentResponse get(Long userId, Long documentId);

    void delete(Long userId, Long documentId);
}
