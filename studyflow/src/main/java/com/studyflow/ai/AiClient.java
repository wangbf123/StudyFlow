package com.studyflow.ai;

public interface AiClient {

    AiResult summarize(String title, String content);

    AiChatResponse chat(AiChatRequest request);
}
