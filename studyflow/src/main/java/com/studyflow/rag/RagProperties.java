package com.studyflow.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "studyflow.rag")
public class RagProperties {

    private int topK = 6;
    private int maxContextChars = 6000;
    private int citationPreviewChars = 300;
    private boolean queryRewriteEnabled = true;
    private int queryRewriteMaxChars = 240;
    private int maxValidatedCitations = 6;
    private boolean hybridEnabled = true;
    private int bm25CandidateSize = 40;
    private int vectorCandidateSize = 40;
    private int vectorScanLimit = 1000;
    private double bm25Weight = 0.45;
    private double vectorWeight = 0.45;
    private double rerankWeight = 0.10;
}
