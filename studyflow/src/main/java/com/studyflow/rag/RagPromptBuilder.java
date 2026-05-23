package com.studyflow.rag;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RagPromptBuilder {

    private final RagProperties properties;

    public RagPrompt build(String question, List<RetrievedChunk> chunks) {
        String systemPrompt = """
                You are StudyFlow, a careful study assistant.
                Answer only from the provided knowledge-base snippets.
                If the snippets are insufficient, say that the current knowledge base is not enough to answer.
                Keep the answer clear for exam review.
                Use the same language as the user's question.
                """;

        StringBuilder context = new StringBuilder();
        List<RetrievedChunk> included = new ArrayList<>();
        int maxChars = Math.max(properties.getMaxContextChars(), 1000);
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            String block = """
                    [%d]
                    Document: %s
                    Chunk: %s
                    Score: %s
                    Content:
                    %s

                    """.formatted(
                    i + 1,
                    chunk.fileName(),
                    chunk.chunkIndex(),
                    chunk.score(),
                    chunk.content()
            );
            if (context.length() + block.length() > maxChars) {
                break;
            }
            context.append(block);
            included.add(chunk);
        }

        String userPrompt = """
                Knowledge-base snippets:
                %s

                User question:
                %s

                Please answer with grounded reasoning and cite the snippet numbers when useful.
                """.formatted(context, question);
        return new RagPrompt(systemPrompt, userPrompt, included);
    }
}
