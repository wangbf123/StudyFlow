package com.studyflow.search;

public record ChunkSearchResult(
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
}
