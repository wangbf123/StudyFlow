package com.studyflow.qa;

import java.util.List;

public record QaRetrieveResponse(
        Long knowledgeBaseId,
        String question,
        Integer topK,
        Integer maxContextChars,
        Integer hitCount,
        Integer includedCount,
        List<QaRetrievedChunkResponse> chunks
) {
}
