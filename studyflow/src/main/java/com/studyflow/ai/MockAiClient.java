package com.studyflow.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "studyflow.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockAiClient implements AiClient {

    @Override
    public AiResult summarize(String title, String content) {
        String normalized = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return new AiResult("No valid text was parsed from this document.", "study,knowledge-base");
        }
        String preview = normalized.length() > 240 ? normalized.substring(0, 240) + "..." : normalized;
        String summary = "Summary of " + title + ": " + preview;
        return new AiResult(summary, "study,review,knowledge-base");
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        String prompt = request.userPrompt() == null ? "" : request.userPrompt().replaceAll("\\s+", " ").trim();
        String question = request.question() == null ? "" : request.question().trim();
        String preview = prompt.length() > 600 ? prompt.substring(0, 600) + "..." : prompt;
        String answer = """
                This is a mock RAG answer generated without calling a real model.

                Question:
                %s

                Answer:
                Based on the retrieved knowledge-base snippets, the answer should be grounded in the cited material. Replace studyflow.ai.provider with openai-compatible and configure an API key to get a real model response.

                Retrieved-context preview:
                %s
                """.formatted(question, preview);
        return new AiChatResponse(answer, "mock-rag", 0, 0);
    }
}
