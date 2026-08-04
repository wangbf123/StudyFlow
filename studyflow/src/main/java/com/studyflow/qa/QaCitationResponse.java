package com.studyflow.qa;

public record QaCitationResponse(
        Long citationId,
        Long documentId,
        Long chunkId,
        Integer chunkIndex,
        Integer pageNumber,
        String parserType,
        String documentName,
        String contentPreview,
        Double score
) {
    public static QaCitationResponse from(QaCitationEntity entity) {
        return new QaCitationResponse(
                entity.getId(),
                entity.getDocumentId(),
                entity.getChunkId(),
                entity.getChunkIndex(),
                entity.getPageNumber(),
                entity.getParserType(),
                entity.getDocumentName(),
                entity.getContentPreview(),
                entity.getScore()
        );
    }
}
