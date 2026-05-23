package com.studyflow.document;

public record DocumentChunkResponse(
        Long id,
        Long documentId,
        Integer chunkIndex,
        String content,
        Integer charCount
) {
    public static DocumentChunkResponse from(DocumentChunkEntity entity) {
        return new DocumentChunkResponse(
                entity.getId(),
                entity.getDocumentId(),
                entity.getChunkIndex(),
                entity.getContent(),
                entity.getCharCount()
        );
    }
}
