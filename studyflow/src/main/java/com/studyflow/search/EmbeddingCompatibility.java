package com.studyflow.search;

import java.util.List;

final class EmbeddingCompatibility {

    private EmbeddingCompatibility() {
    }

    static boolean usable(
            String activeModel,
            List<Double> queryVector,
            List<Double> storedVector,
            String storedModel,
            Integer storedDim
    ) {
        if (queryVector == null || queryVector.isEmpty() || storedVector == null || storedVector.isEmpty()) {
            return false;
        }
        if (storedDim != null && storedDim != queryVector.size()) {
            return false;
        }
        if (storedVector.size() != queryVector.size()) {
            return false;
        }
        return activeModel == null || activeModel.isBlank()
                || storedModel == null || storedModel.isBlank()
                || activeModel.equals(storedModel);
    }
}
