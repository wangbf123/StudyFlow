package com.studyflow.upload;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UploadInitRequest(
        @NotNull Long knowledgeBaseId,
        @NotBlank @Size(max = 255) String fileName,
        @NotNull Long fileSize,
        String fileMd5,
        Long chunkSize,
        Integer totalParts
) {
}
