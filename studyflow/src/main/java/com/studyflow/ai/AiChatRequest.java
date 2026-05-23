package com.studyflow.ai;

public record AiChatRequest(
        String systemPrompt,
        String userPrompt,
        String question,
        String model,
        Double temperature
) {
}
