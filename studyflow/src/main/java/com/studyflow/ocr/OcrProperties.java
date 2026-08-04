package com.studyflow.ocr;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "studyflow.ocr")
public class OcrProperties {

    private boolean enabled = true;
    private String baseUrl = "http://localhost:8866";
    private int connectTimeoutSeconds = 5;
    private int readTimeoutSeconds = 180;
    private int minTextCharsPerPage = 40;
    private int maxPages = 100;
}
