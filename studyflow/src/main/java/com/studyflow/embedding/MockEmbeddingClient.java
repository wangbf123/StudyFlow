package com.studyflow.embedding;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "studyflow.embedding", name = "provider", havingValue = "mock", matchIfMissing = true)
public class MockEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties properties;

    @Override
    public String model() {
        return properties.getModel();
    }

    @Override
    public List<Double> embed(String text) {
        int dimension = Math.max(properties.getDimension(), 16);
        double[] values = new double[dimension];
        List<String> tokens = tokenize(text);
        if (tokens.isEmpty()) {
            tokens = List.of("empty");
        }
        for (String token : tokens) {
            addToken(values, token, 1.0);
        }
        normalize(values);
        List<Double> vector = new ArrayList<>(dimension);
        for (double value : values) {
            vector.add(value);
        }
        return vector;
    }

    private List<String> tokenize(String text) {
        String normalized = text == null ? "" : text;
        int maxChars = Math.max(properties.getMaxInputChars(), 200);
        if (normalized.length() > maxChars) {
            normalized = normalized.substring(0, maxChars);
        }
        normalized = Normalizer.normalize(normalized, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);

        Set<String> tokens = new LinkedHashSet<>();
        for (String raw : normalized.split("[^\\p{L}\\p{N}]+")) {
            if (raw.isBlank()) {
                continue;
            }
            tokens.add(raw);
            if (containsHan(raw)) {
                for (int i = 0; i < raw.length(); i++) {
                    tokens.add(raw.substring(i, i + 1));
                }
            }
            if (raw.length() >= 3) {
                for (int i = 0; i <= raw.length() - 3; i++) {
                    tokens.add(raw.substring(i, i + 3));
                }
            }
        }
        return List.copyOf(tokens);
    }

    private boolean containsHan(String value) {
        return value.codePoints()
                .mapToObj(Character.UnicodeScript::of)
                .anyMatch(script -> script == Character.UnicodeScript.HAN);
    }

    private void addToken(double[] values, String token, double weight) {
        int hash = token.hashCode();
        int index = Math.floorMod(hash, values.length);
        double sign = ((hash >>> 8) & 1) == 0 ? 1.0 : -1.0;
        values[index] += sign * weight;

        int second = Math.floorMod(hash * 31 + 17, values.length);
        values[second] += sign * weight * 0.5;
    }

    private void normalize(double[] values) {
        double norm = 0.0;
        for (double value : values) {
            norm += value * value;
        }
        if (norm == 0.0) {
            return;
        }
        double scale = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < values.length; i++) {
            values[i] = values[i] * scale;
        }
    }
}
