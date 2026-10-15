package com.studyflow.task;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "studyflow.pipeline")
public class PipelineProperties {
    private int leaseSeconds = 60;
    private int batchSize = 20;
    private int maxRetryDelaySeconds = 300;
    private int chunkBatchSize = 64;
    private int summaryMaxChars = 12000;

    public int chunkBatchSize() { return Math.min(100, Math.max(1, chunkBatchSize)); }
    public int summaryMaxChars() { return Math.min(64000, Math.max(256, summaryMaxChars)); }

    public int leaseSeconds() { return Math.max(15, leaseSeconds); }
    public int batchSize() { return Math.min(100, Math.max(1, batchSize)); }
    public int retryDelay(int attempt) {
        return Math.min(Math.max(1, maxRetryDelaySeconds), 1 << Math.min(Math.max(attempt, 0), 16));
    }
}
