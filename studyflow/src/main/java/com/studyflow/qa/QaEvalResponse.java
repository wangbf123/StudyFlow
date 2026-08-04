package com.studyflow.qa;

import java.util.List;

public record QaEvalResponse(
        Long knowledgeBaseId,
        Integer questionCount,
        Double chunkHitRate,
        Double keywordHitRate,
        List<QaEvalQuestionResponse> results
) {
}
