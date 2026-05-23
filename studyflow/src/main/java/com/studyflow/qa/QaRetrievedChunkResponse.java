package com.studyflow.qa;

import com.studyflow.rag.RetrievedChunk;

public record QaRetrievedChunkResponse(
        Long documentId,
        Long chunkId,
        Integer chunkIndex,
        String documentName,
        String content,
        Double score,
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
                chunk.fileName(),
                chunk.content(),
                chunk.score(),
                included
        );
    }
}
