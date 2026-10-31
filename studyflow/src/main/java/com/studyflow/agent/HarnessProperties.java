package com.studyflow.agent;

import lombok.Data;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.*;

@Data
@Component
@ConfigurationProperties(prefix = "studyflow.harness")
public class HarnessProperties {
    private String otlpEndpoint = "";
    private int inputTokens = 8000, outputTokens = 2000, artifactChars = 64000, maxTeamMembers = 3;
    private Map<String, String> routing = new HashMap<>();
    private Map<String, ModelRoute> models = new LinkedHashMap<>();
    private Map<String, McpEndpoint> mcp = new LinkedHashMap<>();
    private boolean sandboxEnabled = false;
    private String sandboxImage = "";

    @Data
    public static class ModelRoute {
        private String provider = "openai-compatible", baseUrl = "", model = "", apiKeyEnv = "";
        private int maxConcurrency = 2, maxAttempts = 2, timeoutSeconds = 30;
    }

    @Data
    public static class McpEndpoint {
        private String url = "", tokenEnv = "";
        private List<String> tools = new ArrayList<>(), resourcePrefixes = new ArrayList<>();
        private boolean enabled = false;
        private int timeoutSeconds = 15;
    }

    public int artifactChars() {
        return Math.clamp(artifactChars, 8000, 256000);
    }

    public int inputs() {
        return Math.clamp(inputTokens, 1500, 64000);
    }

    public int outputs() {
        return Math.clamp(outputTokens, 128, 16000);
    }
}
