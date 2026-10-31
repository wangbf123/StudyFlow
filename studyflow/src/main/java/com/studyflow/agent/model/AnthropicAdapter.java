package com.studyflow.agent.model;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.*;
import com.studyflow.agent.*;
import com.studyflow.ai.*;
import com.studyflow.common.BusinessException;

import java.time.Duration;
import java.util.*;

/**
 * Official Claude SDK. SDK retries are disabled so every attempt is counted by the harness gateway.
 */
public class AnthropicAdapter implements AiClient, AutoCloseable {
    private final AnthropicClient client;
    private final AgentJson json;
    private final String model;

    public AnthropicAdapter(HarnessProperties.ModelRoute route, String key, AgentJson json) {
        var builder =
                AnthropicOkHttpClient.builder()
                        .apiKey(key)
                        .maxRetries(0)
                        .timeout(Duration.ofSeconds(Math.clamp(route.getTimeoutSeconds(), 1, 60)));
        if (!route.getBaseUrl().isBlank()) builder.baseUrl(route.getBaseUrl());
        client = builder.build();
        this.json = json;
        model = route.getModel();
    }

    public AiResult summarize(String title, String content) {
        throw new UnsupportedOperationException("Use the summary route");
    }

    public AiChatResponse chat(AiChatRequest request) {
        var message =
                client.messages()
                        .create(
                                MessageCreateParams.builder()
                                        .model(model)
                                        .maxTokens(
                                                request.maxOutputTokens() == null
                                                        ? 2000
                                                        : request.maxOutputTokens())
                                        .system(request.systemPrompt())
                                        .addUserMessage(request.userPrompt())
                                        .build());
        String text =
                message.content().stream()
                        .filter(ContentBlock::isText)
                        .map(b -> b.asText().text())
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse("");
        return new AiChatResponse(
                text,
                message.model().toString(),
                Math.toIntExact(message.usage().inputTokens()),
                Math.toIntExact(message.usage().outputTokens()));
    }

    public AiToolResponse callTool(AiToolRequest request) {
        var tools =
                request.tools().stream()
                        .map(t ->
                                Map.of(
                                        "name",
                                        t.name(),
                                        "description",
                                        t.description(),
                                        "input_schema",
                                        json.read(t.parameters().toString(), Map.class)))
                        .toList();
        var params =
                MessageCreateParams.builder()
                        .model(model)
                        .maxTokens(
                                request.maxOutputTokens() == null
                                        ? 2000
                                        : request.maxOutputTokens())
                        .system(request.systemPrompt())
                        .addUserMessage(request.userPrompt())
                        .putAdditionalBodyProperty("tools", JsonValue.from(tools))
                        .putAdditionalBodyProperty(
                                "tool_choice",
                                JsonValue.from(
                                        Map.of("type", "any", "disable_parallel_tool_use", true)))
                        .build();
        var message = client.messages().create(params);
        var calls = message.content().stream().filter(ContentBlock::isToolUse).toList();
        if (calls.size() != 1) throw new BusinessException(502, "Claude 必须返回单个工具调用");
        var tool = calls.getFirst().asToolUse();
        if (request.tools().stream().noneMatch(t -> t.name().equals(tool.name())))
            throw new BusinessException(502, "Claude 返回未知工具");
        return new AiToolResponse(
                tool.name(),
                json.tree(json.write(tool._input().convert(Map.class))),
                message.model().toString(),
                Math.toIntExact(message.usage().inputTokens()),
                Math.toIntExact(message.usage().outputTokens()));
    }

    public void close() {
        client.close();
    }
}
