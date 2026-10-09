package com.studyflow.rag;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "studyflow.evidence")
public class EvidenceProperties {
    private int maxClaims = 6;
    private int maxEvidencePerClaim = 3;
    private int maxClaimChars = 2000;
    private int minQuoteChars = 8;
    private int maxQuoteChars = 2000;
    private int maxOutputChars = 64000;
    private int verifierTimeoutSeconds = 20;
    private String verifierModel;
}
