package com.studyflow.qa;

import java.util.List;

public record QaAskResponse(
        Long sessionId,
        Long userMessageId,
        Long assistantMessageId,
        String answer,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Long latencyMs,
        List<QaCitationResponse> citations
) {
}
