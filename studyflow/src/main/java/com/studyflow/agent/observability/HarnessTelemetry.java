package com.studyflow.agent.observability;

import static com.studyflow.agent.AgentTypes.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.studyflow.agent.*;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.*;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@lombok.extern.slf4j.Slf4j
public class HarnessTelemetry {
    private final JdbcTemplate jdbc;
    private final MeterRegistry meters;
    private final SdkTracerProvider provider;
    private final Tracer tracer;

    public HarnessTelemetry(JdbcTemplate jdbc, MeterRegistry meters, HarnessProperties config) {
        this.jdbc = jdbc;
        this.meters = meters;
        var builder = SdkTracerProvider.builder();
        if (!config.getOtlpEndpoint().isBlank())
            builder.addSpanProcessor(
                    BatchSpanProcessor.builder(
                                    OtlpGrpcSpanExporter.builder()
                                            .setEndpoint(config.getOtlpEndpoint())
                                            .build())
                            .build());
        // OTel 1.67 optimizes providers without processors into invalid/no-op contexts.
        // Keep correlation contexts for our JDBC audit even when no remote collector is configured.
        if (config.getOtlpEndpoint().isBlank())
            builder.addSpanProcessor(
                    new io.opentelemetry.sdk.trace.SpanProcessor() {
                        public void onStart(
                                io.opentelemetry.context.Context context,
                                io.opentelemetry.sdk.trace.ReadWriteSpan span) {}

                        public boolean isStartRequired() {
                            return false;
                        }

                        public void onEnd(io.opentelemetry.sdk.trace.ReadableSpan span) {}

                        public boolean isEndRequired() {
                            return false;
                        }
                    });
        provider = builder.build();
        tracer =
                OpenTelemetrySdk.builder()
                        .setTracerProvider(provider)
                        .build()
                        .getTracer("studyflow.harness", "3.0");
    }

    public Span start(Claim run, String purpose, String route) {
        return tracer.spanBuilder("agent.model." + purpose)
                .setAttribute("agent.run.id", run.id())
                .setAttribute("agent.root.id", run.rootId())
                .setAttribute("agent.role", run.role())
                .setAttribute("gen_ai.operation.name", purpose)
                .setAttribute("gen_ai.request.model", route)
                .startSpan();
    }

    public void record(
            Claim run,
            String purpose,
            String route,
            String model,
            String status,
            long millis,
            Integer input,
            Integer output,
            Span span) {
        try {
            jdbc.update(
                    "INSERT INTO"
                        + " agent_model_invocation(id,run_id,purpose,route_name,model_name,status,latency_ms,prompt_tokens,completion_tokens,trace_id,create_time)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,NOW(6))",
                    IdWorker.getId(),
                    run.id(),
                    purpose,
                    route,
                    AgentJson.clip(model, 120),
                    status,
                    millis,
                    input,
                    output,
                    span.getSpanContext().getTraceId());
            meters.timer(
                            "studyflow.harness.model",
                            "route",
                            route,
                            "purpose",
                            purpose,
                            "status",
                            status)
                    .record(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (input != null)
                meters.counter("studyflow.harness.reported_tokens", "route", route, "kind", "input")
                        .increment(input);
            if (output != null)
                meters.counter(
                                "studyflow.harness.reported_tokens",
                                "route",
                                route,
                                "kind",
                                "output")
                        .increment(output);
            span.setAttribute("gen_ai.usage.input_tokens", input == null ? 0 : input)
                    .setAttribute("gen_ai.usage.output_tokens", output == null ? 0 : output);
            if (!status.equals("SUCCESS")) span.setStatus(StatusCode.ERROR);
        } catch (Exception failure) {
            log.warn(
                    "Harness telemetry unavailable: run={}, type={}",
                    run.id(),
                    failure.getClass().getSimpleName());
        } finally {
            span.end();
        }
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        provider.close();
    }
}
