package com.studyflow.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.studyflow.ai.AiProperties;
import com.studyflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "studyflow.embedding", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient {

    private final EmbeddingProperties properties;
    private final AiProperties aiProperties;
    private final RestClient.Builder restClientBuilder;

    @Override
    public String model() {
        return chooseModel();
    }

    @Override
    public List<Double> embed(String text) {
        return embedBatch(List.of(text)).getFirst();
    }

    @Override
    public List<List<Double>> embedBatch(List<String> texts) {
        validateConfig();
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        int batchSize = Math.max(properties.getBatchSize(), 1);
        List<List<Double>> vectors = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            int end = Math.min(texts.size(), start + batchSize);
            vectors.addAll(requestBatch(texts.subList(start, end)));
        }
        return vectors;
    }

    private List<List<Double>> requestBatch(List<String> texts) {
        OpenAiEmbeddingResponse response = restClientBuilder
                .baseUrl(trimTrailingSlash(baseUrl()))
                .requestFactory(requestFactory())
                .build()
                .post()
                .uri("/embeddings")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + apiKey())
                .body(new OpenAiEmbeddingRequest(chooseModel(), texts.stream().map(this::trimInput).toList()))
                .retrieve()
                .body(OpenAiEmbeddingResponse.class);

        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new BusinessException(502, "Embedding provider returned an empty response");
        }
        List<EmbeddingData> ordered = response.data().stream()
                .sorted(Comparator.comparingInt(EmbeddingData::index))
                .toList();
        if (ordered.size() != texts.size()) {
            throw new BusinessException(502, "Embedding provider returned " + ordered.size()
                    + " vectors for " + texts.size() + " inputs");
        }
        return ordered.stream()
                .map(EmbeddingData::embedding)
                .toList();
    }

    private void validateConfig() {
        if (baseUrl().isBlank()) {
            throw new BusinessException(500, "studyflow.embedding.base-url or studyflow.ai.base-url is required");
        }
        if (apiKey().isBlank()) {
            throw new BusinessException(500, "studyflow.embedding.api-key or studyflow.ai.api-key is required");
        }
    }

    private String chooseModel() {
        String model = properties.getModel();
        if (model == null || model.isBlank() || "mock-embedding".equals(model)) {
            return "text-embedding-3-small";
        }
        return model;
    }

    private String baseUrl() {
        return firstNonBlank(properties.getBaseUrl(), aiProperties.getBaseUrl());
    }

    private String apiKey() {
        return firstNonBlank(properties.getApiKey(), aiProperties.getApiKey());
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return second == null ? "" : second.trim();
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private String trimInput(String value) {
        String safe = value == null ? "" : value;
        int maxChars = Math.max(properties.getMaxInputChars(), 200);
        if (safe.length() <= maxChars) {
            return safe;
        }
        return safe.substring(0, maxChars);
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int readTimeoutMs = Math.toIntExact(Duration.ofSeconds(properties.getTimeoutSeconds()).toMillis());
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    private record OpenAiEmbeddingRequest(
            String model,
            List<String> input
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OpenAiEmbeddingResponse(
            List<EmbeddingData> data
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EmbeddingData(
            int index,
            List<Double> embedding
    ) {
    }
}
