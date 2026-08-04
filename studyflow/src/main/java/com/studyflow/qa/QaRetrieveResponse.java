package com.studyflow.qa;

import java.util.List;

public record QaRetrieveResponse(
        Long knowledgeBaseId,
        String question,
        String rewrittenQuery,
        Integer topK,
        Integer maxContextChars,
        String strategy,
        Integer hitCount,
        Integer includedCount,
        List<QaRetrievedChunkResponse> chunks
) {
}
