package com.studyflow.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EmbeddingVectorCodecTest {

    private final EmbeddingVectorCodec codec = new EmbeddingVectorCodec(new ObjectMapper());

    @Test
    void roundTripsVectorJson() {
        List<Double> vector = List.of(0.1, -0.2, 0.3);

        assertEquals(vector, codec.decode(codec.encode(vector)));
    }

    @Test
    void normalizesCosineIntoZeroToOneRange() {
        assertEquals(1.0, codec.normalizedCosine(List.of(1.0, 0.0), List.of(1.0, 0.0)), 0.0001);
        assertEquals(0.5, codec.normalizedCosine(List.of(1.0, 0.0), List.of(0.0, 1.0)), 0.0001);
        assertEquals(0.0, codec.normalizedCosine(List.of(1.0, 0.0), List.of(-1.0, 0.0)), 0.0001);
    }
}
