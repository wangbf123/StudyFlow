package com.studyflow.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingCompatibilityTest {

    @Test
    void acceptsSameModelAndDimension() {
        assertTrue(EmbeddingCompatibility.usable(
                "mock-embedding",
                List.of(0.1, 0.2, 0.3),
                List.of(0.3, 0.2, 0.1),
                "mock-embedding",
                3
        ));
    }

    @Test
    void rejectsMismatchedModel() {
        assertFalse(EmbeddingCompatibility.usable(
                "text-embedding-3-small",
                List.of(0.1, 0.2),
                List.of(0.1, 0.2),
                "mock-embedding",
                2
        ));
    }

    @Test
    void rejectsMismatchedDimension() {
        assertFalse(EmbeddingCompatibility.usable(
                "mock-embedding",
                List.of(0.1, 0.2, 0.3),
                List.of(0.1, 0.2),
                "mock-embedding",
                2
        ));
    }

    @Test
    void allowsMissingStoredModelWhenVectorShapeMatches() {
        assertTrue(EmbeddingCompatibility.usable(
                "mock-embedding",
                List.of(0.1, 0.2),
                List.of(0.2, 0.1),
                null,
                2
        ));
    }
}
