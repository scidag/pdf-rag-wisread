package com.wisread.service;

import com.wisread.entity.Document;
import com.wisread.entity.DocumentJob;

public interface DocumentProcessingService {

    void processDocument(Long documentId, Long userId);

    void handleFailure(Document document, DocumentJob job, Exception exception);
}
