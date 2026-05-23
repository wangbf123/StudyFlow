package com.studyflow.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "studyflow.rag")
public class RagProperties {

    private int topK = 6;
    private int maxContextChars = 6000;
    private int citationPreviewChars = 300;
}
