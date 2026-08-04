package com.studyflow.embedding;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MockEmbeddingClientTest {

    @Test
    void createsDeterministicNormalizedVectors() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setDimension(32);
        MockEmbeddingClient client = new MockEmbeddingClient(properties);

        List<Double> first = client.embed("BM25 and vector retrieval");
        List<Double> second = client.embed("BM25 and vector retrieval");

        assertEquals(32, first.size());
        assertEquals(first, second);
        assertEquals(1.0, norm(first), 0.0001);
    }

    private double norm(List<Double> vector) {
        double sum = 0.0;
        for (Double value : vector) {
            sum += value * value;
        }
        return Math.sqrt(sum);
    }
}
