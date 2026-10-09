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
        Double score,
        Long claimId, String sourceId, String quote, Integer charStart, Integer charEnd,
        String contentHash, Long documentGeneration, String verificationStatus
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
                entity.getScore(),
                entity.getClaimId(), entity.getSourceId(), entity.getQuote(), entity.getCharStart(), entity.getCharEnd(),
                entity.getContentHash(), entity.getDocumentGeneration(), entity.getVerificationStatus()
        );
    }
}
