package com.studyflow.qa;

import java.util.List;

public record QaAskResponse(
        Long sessionId,
        Long userMessageId,
        Long assistantMessageId,
        String answer,
        String rewrittenQuery,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Long latencyMs,
        Boolean structuredOutput,
        Integer validatedCitationCount,
        Integer rejectedCitationCount,
        List<QaCitationResponse> citations
) {
}
