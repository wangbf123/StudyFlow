package com.studyflow.qa;

public record QaCitationDetailResponse(
        Long citationId,
        Long messageId,
        Long documentId,
        Long chunkId,
        Integer chunkIndex,
        Integer pageNumber,
        String parserType,
        Double ocrConfidence,
        String documentName,
        String content,
        String contentPreview,
        Double score
) {
}
