package com.studyflow.upload;

import com.studyflow.document.DocumentUploadResponse;

import java.util.List;

public record UploadInitResponse(
        String uploadId,
        String status,
        Long chunkSize,
        Integer totalParts,
        List<Integer> uploadedParts,
        Integer progress,
        boolean instantUploaded,
        DocumentUploadResponse document
) {
}
