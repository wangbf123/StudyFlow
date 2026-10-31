package com.studyflow.ai;

import com.fasterxml.jackson.databind.JsonNode;

public record AiToolResponse(String name, JsonNode arguments, String model, Integer promptTokens, Integer completionTokens) {}
