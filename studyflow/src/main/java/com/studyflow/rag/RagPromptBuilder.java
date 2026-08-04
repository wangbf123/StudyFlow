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
        return build(question, question, chunks);
    }

    public RagPrompt build(String question, String retrievalQuery, List<RetrievedChunk> chunks) {
        String systemPrompt = """
                You are StudyFlow, a careful trusted RAG answer generator.
                Answer only from the provided knowledge-base snippets.
                If the snippets are insufficient, say that the current knowledge base is not enough to answer.
                Keep the answer clear for exam review.
                Use the same language as the user's question.
                Return exactly one JSON object and no Markdown.
                JSON schema:
                {
                  "answer": "string",
                  "citations": [
                    {
                      "source_id": "S1"
                    }
                  ]
                }
                Citation rules:
                - source_id must be copied exactly from the provided Source ID values.
                - Include only source IDs that directly support the answer.
                - Do not cite a source that is not in the snippets.
                - If the snippets are insufficient, use citations: [].
                """;

        StringBuilder context = new StringBuilder();
        List<RetrievedChunk> included = new ArrayList<>();
        int maxChars = Math.max(properties.getMaxContextChars(), 1000);
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            String block = """
                    [%s]
                    Source ID: %s
                    Document: %s
                    Chunk: %s
                    Page: %s
                    Parser: %s
                    OCR Confidence: %s
                    Source: %s
                    BM25 Score: %s
                    Vector Score: %s
                    Rerank Score: %s
                    Final Score: %s
                    Content:
                    %s

                    """.formatted(
                    sourceId(i),
                    sourceId(i),
                    chunk.fileName(),
                    chunk.chunkIndex(),
                    chunk.pageNumber(),
                    chunk.parserType(),
                    chunk.ocrConfidence(),
                    chunk.source(),
                    chunk.bm25Score(),
                    chunk.vectorScore(),
                    chunk.rerankScore(),
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

                Retrieval query:
                %s

                Original user question:
                %s

                Answer the original user question. Return JSON only.
                """.formatted(context, retrievalQuery, question);
        return new RagPrompt(systemPrompt, userPrompt, included);
    }

    private String sourceId(int zeroBasedIndex) {
        return "S" + (zeroBasedIndex + 1);
    }
}
