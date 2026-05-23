package com.studyflow.search;

public record SearchResponse(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        String fileName,
        Integer chunkIndex,
        String content,
        Double score
) {
    public static SearchResponse from(DocumentChunkIndex index, Double score) {
        return new SearchResponse(
                index.getChunkId(),
                index.getDocumentId(),
                index.getKnowledgeBaseId(),
                index.getFileName(),
                index.getChunkIndex(),
                index.getContent(),
                score
        );
    }
}
