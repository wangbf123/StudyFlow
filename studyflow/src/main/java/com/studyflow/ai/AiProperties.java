package com.studyflow.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "studyflow.ai")
public class AiProperties {

    private String provider = "mock";
    private String baseUrl;
    private String apiKey;
    private String model = "mock-rag";
    private int timeoutSeconds = 60;
    private double temperature = 0.2;
    private int maxOutputChars = 64000;
    private int maxResponseBytes = 262144;
}
