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
        List<QaCitationResponse> citations,
        String answerStatus, String verificationMode, String verifierModel, Long verificationLatencyMs,
        Integer verificationPromptTokens, Integer verificationCompletionTokens, Integer rejectedClaimCount,
        List<QaClaimResponse> claims
) {
    public QaAskResponse(Long sessionId, Long userMessageId, Long assistantMessageId, String answer,
            String rewrittenQuery, String modelName, Integer promptTokens, Integer completionTokens,
            Long latencyMs, Boolean structuredOutput, Integer validatedCitationCount, Integer rejectedCitationCount,
            List<QaCitationResponse> citations) {
        this(sessionId, userMessageId, assistantMessageId, answer, rewrittenQuery, modelName, promptTokens,
                completionTokens, latencyMs, structuredOutput, validatedCitationCount, rejectedCitationCount,
                citations, "NO_CONTEXT", "NONE", null, 0L, 0, 0, 0, List.of());
    }
}
