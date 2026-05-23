package com.studyflow.rag;

public record RetrievedChunk(
        Long chunkId,
        Long documentId,
        Long knowledgeBaseId,
        String fileName,
        Integer chunkIndex,
        String content,
        Double score
) {
}
