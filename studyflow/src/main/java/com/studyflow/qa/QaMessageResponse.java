package com.studyflow.qa;

import java.time.LocalDateTime;
import java.util.List;

public record QaMessageResponse(
        Long id,
        Long sessionId,
        String role,
        String status,
        String content,
        String modelName,
        Integer promptTokens,
        Integer completionTokens,
        Long latencyMs,
        String errorMessage,
        LocalDateTime createTime,
        List<QaCitationResponse> citations
) {
}
