package com.studyflow.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class RagAnswerValidator {

    private static final Pattern SOURCE_MARKER = Pattern.compile("\\[(S?\\d+)\\]", Pattern.CASE_INSENSITIVE);

    private final ObjectMapper objectMapper;
    private final RagProperties ragProperties;

    public RagAnswer parseAndValidate(String rawContent, RagPrompt prompt) {
        ParsedAnswer parsed = parseStructured(rawContent);
        List<RetrievedChunk> contextChunks = prompt == null || prompt.includedChunks() == null
                ? List.of()
                : prompt.includedChunks();
        Map<String, RetrievedChunk> chunksBySourceId = chunksBySourceId(contextChunks);
        Map<Long, RetrievedChunk> chunksByChunkId = chunksByChunkId(contextChunks);

        LinkedHashMap<Long, RetrievedChunk> accepted = new LinkedHashMap<>();
        for (String sourceId : parsed.sourceIds()) {
            RetrievedChunk chunk = chunksBySourceId.get(normalizeSourceId(sourceId));
            if (chunk != null) {
                accepted.putIfAbsent(chunk.chunkId(), chunk);
            }
        }
        for (Long chunkId : parsed.chunkIds()) {
            RetrievedChunk chunk = chunksByChunkId.get(chunkId);
            if (chunk != null) {
                accepted.putIfAbsent(chunk.chunkId(), chunk);
            }
        }

        int maxCitations = Math.max(ragProperties.getMaxValidatedCitations(), 1);
        List<RetrievedChunk> citedChunks = accepted.values().stream()
                .limit(maxCitations)
                .toList();
        Set<String> validSourceIds = validSourceIds(citedChunks, contextChunks);
        String answer = normalizeAnswer(parsed.answer().isBlank() ? rawContent : parsed.answer());
        answer = removeInvalidSourceMarkers(answer, validSourceIds);

        int requestedCitationCount = parsed.sourceIds().size() + parsed.chunkIds().size();
        int rejectedCount = Math.max(requestedCitationCount - citedChunks.size(), 0);
        return new RagAnswer(answer, citedChunks, parsed.structuredOutput(), rejectedCount);
    }

    private ParsedAnswer parseStructured(String rawContent) {
        String raw = normalizeAnswer(rawContent);
        if (raw.isBlank()) {
            return new ParsedAnswer("", List.of(), List.of(), false);
        }
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(raw));
            if (!root.isObject()) {
                return new ParsedAnswer(raw, List.of(), List.of(), false);
            }
            String answer = text(root, "answer", "finalAnswer", "final_answer", "content");
            List<String> sourceIds = new ArrayList<>();
            List<Long> chunkIds = new ArrayList<>();
            collectCitationArray(root.get("citations"), sourceIds, chunkIds);
            collectCitationArray(root.get("sources"), sourceIds, chunkIds);
            collectCitationArray(root.get("source_ids"), sourceIds, chunkIds);
            collectCitationArray(root.get("sourceIds"), sourceIds, chunkIds);
            collectCitationArray(root.get("citation_ids"), sourceIds, chunkIds);
            collectCitationArray(root.get("citationIds"), sourceIds, chunkIds);
            return new ParsedAnswer(answer == null ? raw : answer, sourceIds, chunkIds, answer != null);
        } catch (Exception ex) {
            return new ParsedAnswer(raw, sourceIdsFromInlineMarkers(raw), List.of(), false);
        }
    }

    private void collectCitationArray(JsonNode node, List<String> sourceIds, List<Long> chunkIds) {
        if (node == null || node.isNull()) {
            return;
        }
        if (!node.isArray()) {
            collectCitationNode(node, sourceIds, chunkIds);
            return;
        }
        for (JsonNode item : node) {
            collectCitationNode(item, sourceIds, chunkIds);
        }
    }

    private void collectCitationNode(JsonNode node, List<String> sourceIds, List<Long> chunkIds) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isTextual() || node.isNumber()) {
            sourceIds.add(node.asText());
            return;
        }
        if (!node.isObject()) {
            return;
        }
        addText(node, sourceIds, "source_id", "sourceId", "citation_id", "citationId", "source", "id");
        addIndex(node, sourceIds, "snippet_index", "snippetIndex", "source_index", "sourceIndex", "index");
        addLong(node, chunkIds, "chunk_id", "chunkId");
    }

    private void addText(JsonNode node, List<String> values, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && (value.isTextual() || value.isNumber())) {
                values.add(value.asText());
            }
        }
    }

    private void addIndex(JsonNode node, List<String> values, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.canConvertToInt()) {
                values.add("S" + value.asInt());
            }
        }
    }

    private void addLong(JsonNode node, List<Long> values, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null) {
                continue;
            }
            if (value.canConvertToLong()) {
                values.add(value.asLong());
            } else if (value.isTextual()) {
                try {
                    values.add(Long.parseLong(value.asText().trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    private List<String> sourceIdsFromInlineMarkers(String raw) {
        Matcher matcher = SOURCE_MARKER.matcher(raw);
        LinkedHashSet<String> sourceIds = new LinkedHashSet<>();
        while (matcher.find()) {
            sourceIds.add(matcher.group(1));
        }
        return new ArrayList<>(sourceIds);
    }

    private Map<String, RetrievedChunk> chunksBySourceId(List<RetrievedChunk> chunks) {
        Map<String, RetrievedChunk> result = new LinkedHashMap<>();
        for (int i = 0; i < chunks.size(); i++) {
            result.put(sourceId(i), chunks.get(i));
        }
        return result;
    }

    private Map<Long, RetrievedChunk> chunksByChunkId(List<RetrievedChunk> chunks) {
        Map<Long, RetrievedChunk> result = new LinkedHashMap<>();
        for (RetrievedChunk chunk : chunks) {
            if (chunk.chunkId() != null) {
                result.put(chunk.chunkId(), chunk);
            }
        }
        return result;
    }

    private Set<String> validSourceIds(List<RetrievedChunk> citedChunks, List<RetrievedChunk> contextChunks) {
        Set<Long> citedChunkIds = new LinkedHashSet<>();
        for (RetrievedChunk chunk : citedChunks) {
            citedChunkIds.add(chunk.chunkId());
        }
        Set<String> result = new LinkedHashSet<>();
        for (int i = 0; i < contextChunks.size(); i++) {
            RetrievedChunk chunk = contextChunks.get(i);
            if (citedChunkIds.contains(chunk.chunkId())) {
                result.add(sourceId(i));
            }
        }
        return result;
    }

    private String removeInvalidSourceMarkers(String answer, Set<String> validSourceIds) {
        Matcher matcher = SOURCE_MARKER.matcher(answer);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String normalized = normalizeSourceId(matcher.group(1));
            String replacement = validSourceIds.contains(normalized) ? "[" + normalized + "]" : "";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return normalizeAnswer(result.toString());
    }

    private String normalizeSourceId(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return "";
        }
        if (normalized.matches("\\d+")) {
            return "S" + normalized;
        }
        if (normalized.matches("S\\d+")) {
            return normalized;
        }
        return normalized;
    }

    private String sourceId(int zeroBasedIndex) {
        return "S" + (zeroBasedIndex + 1);
    }

    private String text(JsonNode root, String... names) {
        for (String name : names) {
            JsonNode value = root.get(name);
            if (value != null && value.isTextual()) {
                return value.asText();
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

    private String normalizeAnswer(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("\\s+", " ")
                .replaceAll("\\s+([.,!?;:，。！？；：])", "$1")
                .trim();
    }

    private record ParsedAnswer(
            String answer,
            List<String> sourceIds,
            List<Long> chunkIds,
            boolean structuredOutput
    ) {
    }
}
