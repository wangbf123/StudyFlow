package com.studyflow.ai;

import java.util.function.Consumer;

public interface AiClient {

    AiResult summarize(String title, String content);

    AiChatResponse chat(AiChatRequest request);

    default AiChatResponse streamChat(AiChatRequest request, Consumer<AiStreamChunk> onChunk) {
        AiChatResponse response = chat(request);
        if (response.content() != null && !response.content().isEmpty()) {
            onChunk.accept(AiStreamChunk.delta(response.content(), response.model()));
        }
        onChunk.accept(AiStreamChunk.done(response.model(), response.promptTokens(), response.completionTokens()));
        return response;
    }
}
