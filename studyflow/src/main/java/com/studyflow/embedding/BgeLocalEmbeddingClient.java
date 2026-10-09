package com.studyflow.embedding;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Comparator;
import java.util.List;

/** Local CPU sidecar. Model identity includes a pinned revision, preventing mixed embedding spaces. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "studyflow.embedding", name = "provider", havingValue = "bge-local")
public class BgeLocalEmbeddingClient implements EmbeddingClient {
    private final EmbeddingProperties properties;
    private final RestClient.Builder restClientBuilder;
    private volatile String resolvedModel;

    @Override
    public String model() {
        if (resolvedModel == null) {
            synchronized (this) {
                if (resolvedModel == null) {
                    Health health = client().get().uri("/health").retrieve().body(Health.class);
                    if (health == null || !health.ready() || health.dimension() != 512
                            || health.model() == null || !health.model().matches("BAAI/bge-small-zh-v1\\.5@[0-9a-f]{40}")) {
                        throw new IllegalStateException("BGE sidecar must report a pinned 512-dimensional model");
                    }
                    String expected = properties.getModel();
                    if (expected != null && expected.contains("@") && !expected.equals(health.model())) {
                        throw new IllegalStateException("BGE sidecar model revision differs from application configuration");
                    }
                    resolvedModel = health.model();
                }
            }
        }
        return resolvedModel;
    }

    @Override
    public List<Double> embed(String text) {
        return request(List.of(text), "query").getFirst();
    }

    @Override
    public List<List<Double>> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        var result = new java.util.ArrayList<List<Double>>();
        int batchSize = Math.min(Math.max(properties.getBatchSize(), 1), 64);
        for (int offset = 0; offset < texts.size(); offset += batchSize) {
            result.addAll(request(texts.subList(offset, Math.min(offset + batchSize, texts.size())), "document"));
        }
        return result;
    }

    private List<List<Double>> request(List<String> texts, String inputType) {
        Response response = client().post().uri("/embeddings").contentType(MediaType.APPLICATION_JSON)
                .body(new Request(model(), texts, inputType)).retrieve().body(Response.class);
        if (response == null || !model().equals(response.model()) || response.data() == null
                || response.data().size() != texts.size()) {
            throw new IllegalStateException("Invalid BGE embedding response/model identity");
        }
        List<Data> ordered = response.data().stream().sorted(Comparator.comparingInt(Data::index)).toList();
        for (int i = 0; i < ordered.size(); i++) {
            var vector = ordered.get(i).embedding();
            if (ordered.get(i).index() != i || vector == null || vector.size() != 512
                    || vector.stream().anyMatch(v -> v == null || !Double.isFinite(v))) {
                throw new IllegalStateException("Invalid BGE embedding indices or dimensions");
            }
        }
        return ordered.stream().map(Data::embedding).toList();
    }

    private RestClient client() {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new IllegalStateException("studyflow.embedding.base-url is required for bge-local");
        }
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(Math.multiplyExact(properties.getTimeoutSeconds(), 1000));
        var builder = restClientBuilder.clone().baseUrl(properties.getBaseUrl()).requestFactory(factory);
        if (properties.getApiKey() != null && !properties.getApiKey().isBlank()) {
            builder.defaultHeader("Authorization", "Bearer " + properties.getApiKey());
        }
        return builder.build();
    }

    private record Request(String model, List<String> input, String input_type) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Response(String model, List<Data> data) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Data(int index, List<Double> embedding) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Health(boolean ready, String model, int dimension) { }
}
