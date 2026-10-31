package com.studyflow.agent.model;

import static com.studyflow.agent.AgentTypes.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.agent.*;
import com.studyflow.agent.observability.HarnessTelemetry;
import com.studyflow.agent.state.AgentStateStore;
import com.studyflow.ai.*;
import com.studyflow.common.BusinessException;

import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Function;

@Component
public class ModelGateway {
    private final AiClient fallback;
    private final AiProperties defaults;
    private final HarnessProperties properties;
    private final AgentStateStore store;
    private final HarnessTelemetry telemetry;
    private final com.studyflow.agent.context.ContextManager contexts;
    private final AgentJson json;
    private final ObjectMapper mapper;
    private final ConcurrentMap<String, Route> routes = new ConcurrentHashMap<>();

    private record Route(
            AiClient client,
            String model,
            int attempts,
            Semaphore slots,
            AtomicInteger failures,
            AtomicLong blockedUntil) {}

    public ModelGateway(
            AiClient fallback,
            AiProperties defaults,
            HarnessProperties properties,
            AgentStateStore store,
            HarnessTelemetry telemetry,
            AgentJson json,
            ObjectMapper mapper,
            com.studyflow.agent.context.ContextManager contexts) {
        this.fallback = fallback;
        this.defaults = defaults;
        this.properties = properties;
        this.store = store;
        this.telemetry = telemetry;
        this.json = json;
        this.mapper = mapper;
        this.contexts = contexts;
    }

    private String routeName(String purpose) {
        return properties.getRouting().getOrDefault(purpose, "default");
    }

    public boolean extractive(Claim run) {
        return routeName("verify").equals("default")
                && "mock".equalsIgnoreCase(defaults.getProvider());
    }

    private Route route(String name) {
        return routes.computeIfAbsent(
                name,
                key -> {
                    if (key.equals("default"))
                        return new Route(
                                fallback,
                                defaults.getModel(),
                                1,
                                new Semaphore(4),
                                new AtomicInteger(),
                                new AtomicLong());
                    var config = properties.getModels().get(key);
                    if (config == null) throw new BusinessException(503, "模型路由不存在");
                    String secret = System.getenv(config.getApiKeyEnv());
                    if (secret == null || secret.isBlank())
                        throw new BusinessException(503, "模型凭据尚未配置");
                    AiClient client;
                    if (config.getProvider().equals("anthropic"))
                        client = new AnthropicAdapter(config, secret, json);
                    else if (config.getProvider().equals("openai-compatible")) {
                        var p = new AiProperties();
                        p.setProvider("openai-compatible");
                        p.setModel(config.getModel());
                        p.setBaseUrl(config.getBaseUrl());
                        p.setApiKey(secret);
                        p.setTimeoutSeconds(Math.clamp(config.getTimeoutSeconds(), 1, 60));
                        client = new OpenAiCompatibleAiClient(p, RestClient.builder(), mapper);
                    } else throw new BusinessException(503, "模型协议未启用");
                    return new Route(
                            client,
                            config.getModel(),
                            Math.clamp(config.getMaxAttempts(), 1, 3),
                            new Semaphore(Math.clamp(config.getMaxConcurrency(), 1, 16)),
                            new AtomicInteger(),
                            new AtomicLong());
                });
    }

    public AiChatResponse chat(Claim run, String purpose, AiChatRequest request) {
        var context = contexts.measure(request.systemPrompt(), request.userPrompt(), List.of());
        return invoke(
                run,
                purpose,
                context,
                client -> {
                    var r = route(routeName(purpose));
                    return client.chat(
                            new AiChatRequest(
                                    request.systemPrompt(),
                                    request.userPrompt(),
                                    request.question(),
                                    r.model(),
                                    request.temperature(),
                                    properties.outputs()));
                },
                AiChatResponse::model,
                AiChatResponse::promptTokens,
                AiChatResponse::completionTokens);
    }

    public AiToolResponse tool(Claim run, AiToolRequest request) {
        var context =
                contexts.measure(request.systemPrompt(), request.userPrompt(), request.tools());
        return invoke(
                run,
                "tool",
                context,
                client -> {
                    var r = route(routeName("tool"));
                    return client.callTool(
                            new AiToolRequest(
                                    request.systemPrompt(),
                                    request.userPrompt(),
                                    request.question(),
                                    r.model(),
                                    request.tools(),
                                    properties.outputs()));
                },
                AiToolResponse::model,
                AiToolResponse::promptTokens,
                AiToolResponse::completionTokens);
    }

    private <T> T invoke(
            Claim run,
            String purpose,
            com.studyflow.agent.context.ContextManager.Context context,
            Function<AiClient, T> action,
            Function<T, String> model,
            Function<T, Integer> input,
            Function<T, Integer> output) {
        String name = routeName(purpose);
        Route route = route(name);
        if (System.nanoTime() < route.blockedUntil().get() || !route.slots().tryAcquire())
            throw new BusinessException(429, "模型路由暂时繁忙");
        try {
            for (int attempt = 1; ; attempt++) {
                store.reserveModel(run, context.estimatedTokens() + properties.outputs());
                contexts.record(run, store.modelCalls(run.id()), purpose, context);
                var span = telemetry.start(run, purpose, name);
                long start = System.nanoTime();
                try {
                    T response = action.apply(route.client());
                    store.usage(
                            run,
                            model.apply(response),
                            input.apply(response),
                            output.apply(response));
                    route.failures().set(0);
                    telemetry.record(
                            run,
                            purpose,
                            name,
                            model.apply(response),
                            "SUCCESS",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                            input.apply(response),
                            output.apply(response),
                            span);
                    return response;
                } catch (AgentStateStore.LeaseLost ex) {
                    span.end();
                    throw ex;
                } catch (Exception ex) {
                    telemetry.record(
                            run,
                            purpose,
                            name,
                            route.model(),
                            "FAILED",
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                            null,
                            null,
                            span);
                    store.uncertainUsage(run);
                    if (route.failures().incrementAndGet() >= 3)
                        route.blockedUntil().set(System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
                    boolean retryable =
                            ex instanceof ResourceAccessException
                                    || (ex instanceof BusinessException b
                                            && Set.of(429, 502, 503, 504).contains(b.getCode()));
                    if (!retryable || attempt >= route.attempts())
                        throw new BusinessException(502, "模型调用未完成，请查看运行轨迹后重试");
                    try {
                        Thread.sleep(100L * attempt + ThreadLocalRandom.current().nextInt(80));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new BusinessException(503, "模型调用已中断");
                    }
                }
            }
        } finally {
            route.slots().release();
        }
    }

    public List<Map<String, Object>> describe() {
        var output = new ArrayList<Map<String, Object>>();
        output.add(
                Map.of(
                        "name",
                        "default",
                        "provider",
                        defaults.getProvider(),
                        "model",
                        defaults.getModel()));
        properties
                .getModels()
                .forEach((name, r) ->
                        output.add(
                                Map.of(
                                        "name",
                                        name,
                                        "provider",
                                        r.getProvider(),
                                        "model",
                                        r.getModel(),
                                        "credentialConfigured",
                                        System.getenv().containsKey(r.getApiKeyEnv()))));
        return output;
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        routes.values()
                .forEach(r -> {
                    if (r.client() instanceof AutoCloseable closeable)
                        try {
                            closeable.close();
                        } catch (Exception ignored) {
                        }
                });
    }
}
