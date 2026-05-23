package com.studyflow.document;

public record DocumentUploadResponse(
        Long documentId,
        Long taskId,
        String parseStatus
) {
}
