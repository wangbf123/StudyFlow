package com.studyflow.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "studyflow.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleAiClient implements AiClient {

    private final AiProperties properties;
    private final RestClient.Builder restClientBuilder;
    private final ObjectMapper objectMapper;

    @Override
    public AiResult summarize(String title, String content) {
        AiChatResponse response = chat(new AiChatRequest(
                "You summarize learning materials for students.",
                "Summarize the document named \"" + title + "\" in 3-5 concise bullet points:\n\n" + content,
                "Summarize " + title,
                properties.getModel(),
                properties.getTemperature()
        ));
        return new AiResult(response.content(), "study,review,knowledge-base");
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        validateConfig();
        String model = chooseModel(request.model());
        OpenAiChatRequest body = new OpenAiChatRequest(
                model,
                List.of(
                        new Message("system", request.systemPrompt()),
                        new Message("user", request.userPrompt())
                ),
                request.temperature() == null ? properties.getTemperature() : request.temperature(),
                false, request.maxOutputTokens()
        );

        OpenAiChatResponse response = restClientBuilder.clone()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory())
                .build()
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .body(body)
                .exchange((clientRequest, clientResponse) -> {
                    if (clientResponse.getStatusCode().isError()) {
                        throw new BusinessException(502, "AI provider failed: HTTP " + clientResponse.getStatusCode().value());
                    }
                    int limit = Math.min(Math.max(properties.getMaxResponseBytes(), 1024), 4_194_304);
                    byte[] bytes = clientResponse.getBody().readNBytes(limit + 1);
                    if (bytes.length > limit) throw new BusinessException(502, "AI response exceeds configured size limit");
                    return objectMapper.readValue(bytes, OpenAiChatResponse.class);
                });

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new BusinessException(502, "AI provider returned an empty response");
        }
        Choice first = response.choices().getFirst();
        String content = first.message() == null ? null : first.message().content();
        if (content != null && content.length() > properties.getMaxOutputChars()) {
            throw new BusinessException(502, "AI output exceeds configured size limit");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(502, "AI provider returned an empty answer");
        }
        Usage usage = response.usage();
        return new AiChatResponse(
                content,
                response.model() == null ? model : response.model(),
                usage == null ? null : usage.promptTokens(),
                usage == null ? null : usage.completionTokens()
        );
    }

    @Override
    public AiToolResponse callTool(AiToolRequest request) {
        validateConfig();
        String model = chooseModel(request.model());
        var definitions = request.tools().stream().map(tool -> java.util.Map.of("type", "function", "function",
                java.util.Map.of("name", tool.name(), "description", tool.description(), "parameters", tool.parameters()))).toList();
        var body = java.util.Map.of("model", model, "messages", List.of(
                new Message("system", request.systemPrompt()), new Message("user", request.userPrompt())),
                "tools", definitions, "tool_choice", "required", "parallel_tool_calls", false, "stream", false, "max_tokens", request.maxOutputTokens()==null?2000:request.maxOutputTokens());
        var root = restClientBuilder.clone().baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory()).build().post().uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + properties.getApiKey())
                .body(body).exchange((req, res) -> {
                    if (res.getStatusCode().isError()) throw new BusinessException(502, "AI tool provider failed: HTTP " + res.getStatusCode().value());
                    int limit = Math.clamp(properties.getMaxResponseBytes(), 1024, 4_194_304);
                    byte[] bytes = res.getBody().readNBytes(limit + 1);
                    if (bytes.length > limit) throw new BusinessException(502, "AI tool response exceeds size limit");
                    return objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                            .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(bytes);
                });
        if (root == null || !root.path("choices").isArray() || root.path("choices").size() != 1) throw new BusinessException(502, "Expected one tool choice");
        var calls = root.path("choices").get(0).path("message").path("tool_calls");
        if (!calls.isArray() || calls.size() != 1 || !"function".equals(calls.get(0).path("type").asText()))
            throw new BusinessException(502, "Expected exactly one function call");
        var function = calls.get(0).path("function");
        String name = function.path("name").asText("");
        if (request.tools().stream().noneMatch(tool -> tool.name().equals(name))) throw new BusinessException(502, "Unknown tool returned by provider");
        try {
            String raw = function.path("arguments").textValue();
            if (raw == null || raw.length() > 8000) throw new IllegalArgumentException();
            var args = objectMapper.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readTree(raw);
            if (!args.isObject()) throw new IllegalArgumentException();
            var usage = root.path("usage");
            return new AiToolResponse(name, args, root.path("model").asText(model),
                    tokenCount(usage.path("prompt_tokens")), tokenCount(usage.path("completion_tokens")));
        } catch (Exception ex) { throw new BusinessException(502, "Invalid tool arguments from provider"); }
    }

    private Integer tokenCount(com.fasterxml.jackson.databind.JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0 ? value.asInt() : null;
    }

    @Override
    public AiChatResponse streamChat(AiChatRequest request, Consumer<AiStreamChunk> onChunk) {
        validateConfig();
        String model = chooseModel(request.model());
        OpenAiChatRequest body = new OpenAiChatRequest(
                model,
                List.of(
                        new Message("system", request.systemPrompt()),
                        new Message("user", request.userPrompt())
                ),
                request.temperature() == null ? properties.getTemperature() : request.temperature(),
                true, request.maxOutputTokens()
        );

        StringBuilder content = new StringBuilder();
        AtomicReference<String> responseModel = new AtomicReference<>(model);
        AtomicInteger promptTokens = new AtomicInteger(-1);
        AtomicInteger completionTokens = new AtomicInteger(-1);

        restClientBuilder.clone()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory())
                .build()
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .body(body)
                .exchange((clientRequest, clientResponse) -> {
                    if (clientResponse.getStatusCode().isError()) {
                        throw new BusinessException(502, "AI stream provider failed: HTTP "
                                + clientResponse.getStatusCode().value());
                    }
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            clientResponse.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = readBoundedLine(reader)) != null) {
                            if (!line.startsWith("data:")) {
                                continue;
                            }
                            String data = line.substring(5).trim();
                            if (data.isBlank()) {
                                continue;
                            }
                            if ("[DONE]".equals(data)) {
                                break;
                            }
                            handleStreamData(data, responseModel, promptTokens, completionTokens, content, onChunk);
                        }
                    }
                    return null;
                });

        Integer prompt = promptTokens.get() < 0 ? null : promptTokens.get();
        Integer completion = completionTokens.get() < 0 ? null : completionTokens.get();
        onChunk.accept(AiStreamChunk.done(responseModel.get(), prompt, completion));
        return new AiChatResponse(content.toString(), responseModel.get(), prompt, completion);
    }

    private String readBoundedLine(BufferedReader reader) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int limit = Math.min(Math.max(properties.getMaxResponseBytes(), 1024), 4_194_304);
        int ch;
        while ((ch = reader.read()) != -1) {
            if (ch == '\n') return line.toString();
            if (ch == '\r') continue;
            if (line.length() >= limit) throw new BusinessException(502, "AI event exceeds configured size limit");
            line.append((char) ch);
        }
        return line.isEmpty() ? null : line.toString();
    }

    private void validateConfig() {
        if (properties.getBaseUrl() == null || properties.getBaseUrl().isBlank()) {
            throw new BusinessException(500, "studyflow.ai.base-url is required");
        }
        if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
            throw new BusinessException(500, "studyflow.ai.api-key is required");
        }
    }

    private String chooseModel(String requestModel) {
        if (requestModel != null && !requestModel.isBlank()) {
            return requestModel;
        }
        if (properties.getModel() == null || properties.getModel().isBlank()) {
            throw new BusinessException(500, "studyflow.ai.model is required");
        }
        return properties.getModel();
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private SimpleClientHttpRequestFactory requestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        int readTimeoutMs = Math.toIntExact(Duration.ofSeconds(properties.getTimeoutSeconds()).toMillis());
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    private void handleStreamData(
            String data,
            AtomicReference<String> responseModel,
            AtomicInteger promptTokens,
            AtomicInteger completionTokens,
            StringBuilder content,
            Consumer<AiStreamChunk> onChunk
    ) {
        try {
            OpenAiChatStreamResponse chunk = objectMapper.readValue(data, OpenAiChatStreamResponse.class);
            if (chunk.model() != null && !chunk.model().isBlank()) {
                responseModel.set(chunk.model());
            }
            Usage usage = chunk.usage();
            if (usage != null) {
                if (usage.promptTokens() != null) {
                    promptTokens.set(usage.promptTokens());
                }
                if (usage.completionTokens() != null) {
                    completionTokens.set(usage.completionTokens());
                }
            }
            if (chunk.choices() == null || chunk.choices().isEmpty()) {
                return;
            }
            Delta delta = chunk.choices().getFirst().delta();
            String deltaContent = delta == null ? null : delta.content();
            if (deltaContent == null || deltaContent.isEmpty()) {
                return;
            }
            if (content.length() + deltaContent.length() > properties.getMaxOutputChars()) {
                throw new BusinessException(502, "AI stream exceeds configured size limit");
            }
            content.append(deltaContent);
            onChunk.accept(AiStreamChunk.delta(deltaContent, responseModel.get()));
        } catch (Exception ex) {
            throw new BusinessException(502, "Parse AI stream chunk failed: " + ex.getMessage());
        }
    }

    private record OpenAiChatRequest(
            String model,
            List<Message> messages,
            Double temperature,
            Boolean stream,
            @JsonProperty("max_tokens") @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) Integer maxTokens
    ) {
    }

    private record Message(
            String role,
            String content
    ) {
    }

    private record OpenAiChatResponse(
            String model,
            List<Choice> choices,
            Usage usage
    ) {
    }

    private record OpenAiChatStreamResponse(
            String model,
            List<StreamChoice> choices,
            Usage usage
    ) {
    }

    private record Choice(
            Message message
    ) {
    }

    private record StreamChoice(
            Delta delta
    ) {
    }

    private record Delta(
            String content
    ) {
    }

    private record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens
    ) {
    }
}
