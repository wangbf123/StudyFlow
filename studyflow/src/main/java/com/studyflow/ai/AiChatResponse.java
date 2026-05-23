package com.studyflow.ai;

public record AiChatResponse(
        String content,
        String model,
        Integer promptTokens,
        Integer completionTokens
) {
}
