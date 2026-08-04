package com.studyflow.upload;

import com.studyflow.document.DocumentUploadResponse;

import java.util.List;

public record UploadProgressResponse(
        String uploadId,
        String fileName,
        Long fileSize,
        Long chunkSize,
        Integer totalParts,
        List<Integer> uploadedParts,
        Integer progress,
        String status,
        DocumentUploadResponse document
) {
}
