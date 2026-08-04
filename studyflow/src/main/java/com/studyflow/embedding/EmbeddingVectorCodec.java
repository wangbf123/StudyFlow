package com.studyflow.embedding;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class EmbeddingVectorCodec {

    private static final TypeReference<List<Double>> VECTOR_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;

    public String encode(List<Double> vector) {
        if (vector == null || vector.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(vector);
        } catch (Exception ex) {
            throw new IllegalStateException("Encode embedding vector failed: " + ex.getMessage(), ex);
        }
    }

    public List<Double> decode(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, VECTOR_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }

    public double normalizedCosine(List<Double> left, List<Double> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0;
        }
        int size = Math.min(left.size(), right.size());
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int i = 0; i < size; i++) {
            double l = nullToZero(left.get(i));
            double r = nullToZero(right.get(i));
            dot += l * r;
            leftNorm += l * l;
            rightNorm += r * r;
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) {
            return 0.0;
        }
        double cosine = dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
        return clamp((cosine + 1.0) / 2.0);
    }

    private double nullToZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private double clamp(double value) {
        if (value < 0.0) {
            return 0.0;
        }
        if (value > 1.0) {
            return 1.0;
        }
        return value;
    }
}
