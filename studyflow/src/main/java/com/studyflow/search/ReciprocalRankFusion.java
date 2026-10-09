package com.studyflow.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** License-independent RRF. Each item contributes once per retrieval list. */
public final class ReciprocalRankFusion {
    private ReciprocalRankFusion() { }

    public static Map<Long, Double> scores(List<Long> lexical, List<Long> semantic, int constant) {
        if (constant < 1) {
            throw new IllegalArgumentException("RRF constant must be positive");
        }
        Map<Long, Double> result = new LinkedHashMap<>();
        add(result, lexical, constant);
        add(result, semantic, constant);
        return result;
    }

    private static void add(Map<Long, Double> result, List<Long> ids, int constant) {
        var seen = new java.util.HashSet<Long>();
        for (int i = 0; i < ids.size(); i++) {
            Long id = ids.get(i);
            if (id != null && seen.add(id)) {
                result.merge(id, 1.0 / (constant + i + 1), Double::sum);
            }
        }
    }
}
