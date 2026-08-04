package com.studyflow.upload;

import java.util.List;

public record UploadPartResponse(
        String uploadId,
        Integer partNumber,
        List<Integer> uploadedParts,
        Integer progress
) {
}
