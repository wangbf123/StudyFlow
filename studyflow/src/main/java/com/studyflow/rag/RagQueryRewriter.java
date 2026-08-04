package com.studyflow.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.ai.AiChatRequest;
import com.studyflow.ai.AiChatResponse;
import com.studyflow.ai.AiClient;
import com.studyflow.ai.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RagQueryRewriter {

    private final RagProperties ragProperties;
    private final AiClient aiClient;
    private final AiProperties aiProperties;
    private final ObjectMapper objectMapper;

    public RagQueryRewriteResult rewrite(String question) {
        String original = normalize(question);
        if (!ragProperties.isQueryRewriteEnabled() || original.isBlank()) {
            return RagQueryRewriteResult.unchanged(original);
        }
        try {
            AiChatResponse response = aiClient.chat(new AiChatRequest(
                    systemPrompt(),
                    userPrompt(original),
                    original,
                    aiProperties.getModel(),
                    0.0
            ));
            String rewritten = limit(normalize(extractQuery(response.content())),
                    Math.max(ragProperties.getQueryRewriteMaxChars(), 40));
            if (rewritten.isBlank()) {
                return RagQueryRewriteResult.unchanged(original);
            }
            return new RagQueryRewriteResult(original, rewritten, !rewritten.equalsIgnoreCase(original));
        } catch (Exception ex) {
            log.warn("RAG query rewrite failed, fallback to original question: {}", ex.getMessage());
            return RagQueryRewriteResult.unchanged(original);
        }
    }

    private String systemPrompt() {
        return """
                You are a search-query rewriter for a RAG retriever.
                Convert the user question into one concise retrieval query.
                Preserve named entities, technical terms, acronyms, code words, and the user's language.
                Do not answer the question.
                Return exactly one JSON object:
                {"query":"rewritten search query"}
                """;
    }

    private String userPrompt(String question) {
        return "Original question:\n" + question;
    }

    private String extractQuery(String content) throws Exception {
        JsonNode root = objectMapper.readTree(extractJsonObject(content));
        JsonNode query = firstText(root, "query", "rewrittenQuery", "rewritten_query", "searchQuery", "search_query");
        return query == null ? "" : query.asText();
    }

    private JsonNode firstText(JsonNode root, String... names) {
        if (root == null || !root.isObject()) {
            return null;
        }
        for (String name : names) {
            JsonNode node = root.get(name);
            if (node != null && node.isTextual()) {
                return node;
            }
        }
        return null;
    }

    private String extractJsonObject(String content) {
        String value = content == null ? "" : content.trim();
        if (value.startsWith("```")) {
            int firstLine = value.indexOf('\n');
            int lastFence = value.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                value = value.substring(firstLine + 1, lastFence).trim();
            }
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return value.substring(start, end + 1);
        }
        return value;
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength).trim();
    }
}
