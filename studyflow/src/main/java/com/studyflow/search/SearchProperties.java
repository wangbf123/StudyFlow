package com.studyflow.search;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "studyflow.search")
public class SearchProperties {
    /** A new index avoids reinterpreting the old dynamically inferred vector mapping. */
    private String index = "studyflow_document_chunk_v2";
    private RetrievalMode mode = RetrievalMode.RRF;
    private int rrfConstant = 60;
    private int knnCandidates = 200;
    private int maxCandidates = 200;

    public int rrfConstant() { return Math.max(1, rrfConstant); }
    public int maxCandidates() { return Math.min(1000, Math.max(10, maxCandidates)); }

    public enum RetrievalMode {
        BM25, VECTOR, RRF, RRF_LEXICAL, LEGACY
    }
}
