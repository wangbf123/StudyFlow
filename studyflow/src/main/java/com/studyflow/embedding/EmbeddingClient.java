package com.studyflow.embedding;

import java.util.List;

public interface EmbeddingClient {

    String model();

    List<Double> embed(String text);

    default List<List<Double>> embedBatch(List<String> texts) {
        return texts.stream()
                .map(this::embed)
                .toList();
    }
}
