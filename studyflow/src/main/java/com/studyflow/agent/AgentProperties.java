package com.studyflow.agent;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "studyflow.agent")
public class AgentProperties {
    private boolean enabled = true;
    private int maxSteps = 6;
    private int maxModelCalls = 16;
    private int maxReportedTokens = 40_000;
    private int maxHistoryMessages = 6;
    private int maxToolResultChars = 8_000;
    private int maxSources = 12;
    private int maxReadChunks = 6;
    private int runTimeoutSeconds = 300;
    private int actionTimeoutSeconds = 60;
    private int maxAttempts = 3;

    public int steps() {
        return Math.clamp(maxSteps, 1, 12);
    }

    public int calls() {
        return Math.clamp(maxModelCalls, 4, 40);
    }

    public int tokens() {
        return Math.clamp(maxReportedTokens, 1000, 200_000);
    }

    public int resultChars() {
        return Math.clamp(maxToolResultChars, 1000, 16_000);
    }

    public int sources() {
        return Math.clamp(maxSources, 1, 20);
    }

    public int timeout() {
        return Math.clamp(actionTimeoutSeconds, 2, 120);
    }

    public int leaseSeconds() {
        return timeout() + 20;
    }

    public int deadlineSeconds() {
        return Math.clamp(runTimeoutSeconds, 15, 1800);
    }

    public int attempts() {
        return Math.clamp(maxAttempts, 1, 5);
    }
}
