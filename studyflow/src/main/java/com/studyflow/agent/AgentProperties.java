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
    private int maxHistoryMessages = 8;
    private int maxToolResultChars = 12_000;
    private int maxSources = 12;
    private int maxReadChunks = 8;
}
