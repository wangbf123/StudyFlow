package com.studyflow.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperty(prefix = "studyflow.ai", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockAiClient implements AiClient {

    private static final Pattern SOURCE_ID_PATTERN = Pattern.compile("Source ID:\\s*(S\\d+)", Pattern.CASE_INSENSITIVE);

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
        if (contains(request.systemPrompt(), "search-query rewriter")) {
            String query = request.question() == null ? "" : request.question().replaceAll("\\s+", " ").trim();
            return new AiChatResponse("{\"query\":\"" + escapeJson(query) + "\"}", "mock-rag", 0, 0);
        }
        if (contains(request.systemPrompt(), "StudyFlow task planner")) {
            return new AiChatResponse("{\"steps\":[\"检查知识库资料范围\",\"检索目标相关证据并阅读原文\",\"核验结论并生成带引用的报告\"]}", "mock-agent-planner", 0, 0);
        }
        if (contains(request.systemPrompt(), "trusted RAG answer generator")) {
            return structuredRagAnswer(request);
        }

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

    private AiChatResponse structuredRagAnswer(AiChatRequest request) {
        String prompt = request.userPrompt() == null ? "" : request.userPrompt();
        String question = request.question() == null ? "" : request.question().trim();
        // Deterministic extractive mode: exact text, never pretend mock inference verifies semantics.
        Matcher blocks = Pattern.compile("Source ID: (S[0-9]+).*?Content:\n(.*?)(?=\n\n\\[S[0-9]+\\]|\n\n\nRetrieval query:|\\z)", Pattern.DOTALL).matcher(prompt);
        java.util.List<java.util.Map<String, Object>> claims = new java.util.ArrayList<>();
        while (blocks.find() && claims.size() < 2) {
            String text = blocks.group(2).strip();
            int length = Math.min(text.length(), 1200);
            if (length > 0 && Character.isHighSurrogate(text.charAt(length - 1))) length--;
            String quote = text.substring(0, length);
            if (quote.length() >= 8) claims.add(java.util.Map.of("text", quote, "evidence",
                    java.util.List.of(java.util.Map.of("source_id", blocks.group(1), "quote", quote))));
        }
        try {
            return new AiChatResponse(new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(java.util.Map.of("claims", claims)), "mock-extractive", 0, 0);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    @Override
    public AiToolResponse callTool(AiToolRequest request) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var context = mapper.readTree(request.userPrompt());
            var observations = context.path("observations");
            java.util.Set<String> used=new java.util.HashSet<>();String noteId=null;
            for(var observation:observations) {
                used.add(observation.path("tool").asText());
                if("search_notes".equals(observation.path("tool").asText())) {
                    // Compacted observations may contain a non-JSON excerpt or only a result reference.
                    try {
                        var items=mapper.readTree(observation.path("content").asText("{}" )).path("items");
                        if(!items.isEmpty())noteId=items.get(0).path("id").asText();
                    } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { }
                }
            }
            var explicit=java.util.regex.Pattern.compile("note:([0-9]+)").matcher(request.question()==null?"":request.question());
            if(explicit.find())noteId=explicit.group(1);
            if(!context.path("contextNoteId").asText("").isBlank())noteId=context.path("contextNoteId").asText();
            if(!used.contains("knowledge_overview"))return new AiToolResponse("knowledge_overview",mapper.createObjectNode(),"mock-agent-tools",0,0);
            if(!used.contains("search_notes"))return new AiToolResponse("search_notes",mapper.createObjectNode().put("query","").put("size",5),"mock-agent-tools",0,0);
            if(noteId!=null&&!used.contains("read_note"))return new AiToolResponse("read_note",mapper.createObjectNode().put("noteId",noteId),"mock-agent-tools",0,0);
            if(noteId!=null&&!used.contains("explore_graph"))return new AiToolResponse("explore_graph",mapper.createObjectNode().put("noteId",noteId),"mock-agent-tools",0,0);
            if(!used.contains("search_knowledge"))return new AiToolResponse("search_knowledge",mapper.createObjectNode().put("query",request.question()).put("topK",5),"mock-agent-tools",0,0);
            return new AiToolResponse("finish_research",mapper.createObjectNode(),"mock-agent-tools",0,0);
        } catch (Exception ex) { throw new IllegalStateException("Invalid mock agent context", ex); }
    }

    private Set<String> extractSourceIds(String prompt) {
        Matcher matcher = SOURCE_ID_PATTERN.matcher(prompt);
        Set<String> sourceIds = new LinkedHashSet<>();
        while (matcher.find()) {
            sourceIds.add(matcher.group(1).toUpperCase());
        }
        return sourceIds;
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase().contains(keyword.toLowerCase());
    }

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }

    @Override
    public AiChatResponse streamChat(AiChatRequest request, Consumer<AiStreamChunk> onChunk) {
        AiChatResponse response = chat(request);
        String content = response.content() == null ? "" : response.content();
        int step = 48;
        for (int i = 0; i < content.length(); i += step) {
            String delta = content.substring(i, Math.min(i + step, content.length()));
            onChunk.accept(AiStreamChunk.delta(delta, response.model()));
            sleepQuietly(20);
        }
        onChunk.accept(AiStreamChunk.done(response.model(), response.promptTokens(), response.completionTokens()));
        return response;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
