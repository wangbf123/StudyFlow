package com.studyflow.qa;

public record QaCitationDetailResponse(
        Long citationId,
        Long messageId,
        Long documentId,
        Long chunkId,
        Integer chunkIndex,
        String documentName,
        String content,
        String contentPreview,
        Double score
) {
}
