package com.studyflow.agent;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class AgentJson {
    private final ObjectMapper mapper;

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Agent state serialization failed", ex);
        }
    }

    public <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (Exception ex) {
            throw new IllegalStateException("Unsupported agent checkpoint", ex);
        }
    }

    public JsonNode tree(String value) {
        try {
            return mapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                    .readTree(value);
        } catch (Exception ex) {
            throw new IllegalArgumentException("Expected strict JSON");
        }
    }

    public List<String> plan(String raw) {
        if (raw == null || raw.length() > 4000)
            throw new IllegalArgumentException("Plan exceeds size limit");
        var root = tree(raw);
        if (!root.isObject()
                || root.size() != 1
                || !root.path("steps").isArray()
                || root.path("steps").isEmpty()
                || root.path("steps").size() > 5)
            throw new IllegalArgumentException("Invalid plan schema");
        var steps = new ArrayList<String>();
        for (var step : root.path("steps")) {
            if (!step.isTextual() || step.asText().isBlank() || step.asText().length() > 160)
                throw new IllegalArgumentException("Invalid plan step");
            steps.add(step.asText());
        }
        return List.copyOf(steps);
    }

    public static String clip(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        int end = max;
        if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    public String canonical(JsonNode value) {
        if (value.isObject()) {
            var ordered = new TreeMap<String, JsonNode>();
            value.fields().forEachRemaining(entry -> ordered.put(entry.getKey(), entry.getValue()));
            return write(ordered); // Tool schemas only allow scalar properties.
        }
        return write(value);
    }
}
