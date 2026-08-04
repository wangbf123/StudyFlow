package com.studyflow.document;

public record DocumentChunkResponse(
        Long id,
        Long documentId,
        Integer chunkIndex,
        String content,
        Integer charCount,
        Integer pageNumber,
        String parserType,
        Double ocrConfidence
) {
    public static DocumentChunkResponse from(DocumentChunkEntity entity) {
        return new DocumentChunkResponse(
                entity.getId(),
                entity.getDocumentId(),
                entity.getChunkIndex(),
                entity.getContent(),
                entity.getCharCount(),
                entity.getPageNumber(),
                entity.getParserType(),
                entity.getOcrConfidence()
        );
    }
}
