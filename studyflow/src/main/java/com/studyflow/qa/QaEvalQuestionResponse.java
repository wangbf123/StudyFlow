package com.studyflow.qa;

import java.util.List;

public record QaEvalQuestionResponse(
        String question,
        Integer retrievedCount,
        List<Long> retrievedChunkIds,
        Boolean chunkHit,
        Boolean keywordHit,
        List<QaRetrievedChunkResponse> chunks
) {
}
