package com.studyflow.embedding;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "studyflow.embedding")
public class EmbeddingProperties {

    private boolean enabled = true;
    private String provider = "mock";
    private String baseUrl;
    private String apiKey;
    private String model = "mock-embedding";
    private int dimension = 256;
    private int batchSize = 16;
    private int maxInputChars = 8000;
    private int timeoutSeconds = 60;
}
