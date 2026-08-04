package com.studyflow.qa;

import com.studyflow.rag.RetrievedChunk;

public record QaRetrievedChunkResponse(
        Long documentId,
        Long chunkId,
        Integer chunkIndex,
        Integer pageNumber,
        String parserType,
        Double ocrConfidence,
        String documentName,
        String content,
        Double score,
        Double bm25Score,
        Double vectorScore,
        Double rerankScore,
        String source,
        Boolean included
) {
    public static QaRetrievedChunkResponse from(RetrievedChunk chunk) {
        return from(chunk, true);
    }

    public static QaRetrievedChunkResponse from(RetrievedChunk chunk, boolean included) {
        return new QaRetrievedChunkResponse(
                chunk.documentId(),
                chunk.chunkId(),
                chunk.chunkIndex(),
                chunk.pageNumber(),
                chunk.parserType(),
                chunk.ocrConfidence(),
                chunk.fileName(),
                chunk.content(),
                chunk.score(),
                chunk.bm25Score(),
                chunk.vectorScore(),
                chunk.rerankScore(),
                chunk.source(),
                included
        );
    }
}
