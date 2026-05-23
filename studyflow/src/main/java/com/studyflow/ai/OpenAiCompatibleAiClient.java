package com.studyflow.ai;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.studyflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "studyflow.ai", name = "provider", havingValue = "openai-compatible")
public class OpenAiCompatibleAiClient implements AiClient {

    private final AiProperties properties;
    private final RestClient.Builder restClientBuilder;

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
                request.temperature() == null ? properties.getTemperature() : request.temperature()
        );

        OpenAiChatResponse response = restClientBuilder
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .requestFactory(requestFactory())
                .build()
                .post()
                .uri("/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + properties.getApiKey())
                .body(body)
                .retrieve()
                .body(OpenAiChatResponse.class);

        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new BusinessException(502, "AI provider returned an empty response");
        }
        Choice first = response.choices().getFirst();
        String content = first.message() == null ? null : first.message().content();
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

    private record OpenAiChatRequest(
            String model,
            List<Message> messages,
            Double temperature
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

    private record Choice(
            Message message
    ) {
    }

    private record Usage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens
    ) {
    }
}
