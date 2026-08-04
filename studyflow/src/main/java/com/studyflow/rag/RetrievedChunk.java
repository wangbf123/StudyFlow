package com.studyflow.rag;

public record RetrievedChunk(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        String fileName,
        Integer chunkIndex,
        Integer pageNumber,
        String parserType,
        Double ocrConfidence,
        String content,
        Double bm25Score,
        Double vectorScore,
        Double rerankScore,
        String source,
        Double score
) {
    public RetrievedChunk(
            Long chunkId,
            Long documentId,
            Long knowledgeBaseId,
            String fileName,
            Integer chunkIndex,
            String content,
            Double bm25Score,
            Double vectorScore,
            Double rerankScore,
            String source,
            Double score
    ) {
        this(chunkId, documentId, knowledgeBaseId, fileName, chunkIndex,
                null, null, null, content, bm25Score, vectorScore, rerankScore, source, score);
    }
}
