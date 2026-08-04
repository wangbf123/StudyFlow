package com.studyflow.search;

public record SearchResponse(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        String fileName,
        Integer chunkIndex,
        Integer pageNumber,
        String parserType,
        Double ocrConfidence,
        String content,
        Double score,
        Double bm25Score,
        Double vectorScore,
        Double rerankScore,
        String source,
        Boolean hasEmbedding,
        String embeddingModel,
        Integer embeddingDim
) {
    public static SearchResponse from(ChunkSearchResult result) {
        return new SearchResponse(
                result.chunkId(),
                result.documentId(),
                result.knowledgeBaseId(),
                result.fileName(),
                result.chunkIndex(),
                result.pageNumber(),
                result.parserType(),
                result.ocrConfidence(),
                result.content(),
                result.score(),
                result.bm25Score(),
                result.vectorScore(),
                result.rerankScore(),
                result.source(),
                result.hasEmbedding(),
                result.embeddingModel(),
                result.embeddingDim()
        );
    }
}
