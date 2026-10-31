package com.studyflow.agent.runtime;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.*;
import com.studyflow.agent.loop.AgentGraph;
import com.studyflow.agent.state.AgentStateStore;

import io.micrometer.core.instrument.MeterRegistry;

import jakarta.annotation.PreDestroy;

import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.*;

/** Independent bounded worker pools. No HTTP connection owns an execution. */
@Slf4j
@Component
public class AgentRuntime {
    private final AgentStateStore store;
    private final AgentGraph graph;
    private final AgentProperties properties;
    private final MeterRegistry meters;
    private final Semaphore capacity = new Semaphore(4);
    private final ExecutorService workers = pool("agent-dispatch-", 4);
    private final ExecutorService actions = pool("agent-action-", 4);

    public AgentRuntime(
            AgentStateStore store,
            AgentGraph graph,
            AgentProperties properties,
            MeterRegistry meters) {
        this.store = store;
        this.graph = graph;
        this.properties = properties;
        this.meters = meters;
        meters.gauge("studyflow.agent.workers.active", capacity, s -> 4 - s.availablePermits());
    }

    private static ExecutorService pool(String prefix, int count) {
        return new ThreadPoolExecutor(
                count,
                count,
                0,
                TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                Thread.ofPlatform().daemon().name(prefix, 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Scheduled(
            fixedDelayString = "${studyflow.agent.scheduler-delay-ms:1000}",
            initialDelayString = "${studyflow.agent.scheduler-initial-delay-ms:3000}")
    public void dispatch() {
        if (!properties.isEnabled()) return;
        try {
            for (Long id : store.due()) {
                if (!capacity.tryAcquire()) break;
                try {
                    workers.execute(() -> {
                        try {
                            process(id);
                        } finally {
                            capacity.release();
                        }
                    });
                } catch (RejectedExecutionException ex) {
                    capacity.release();
                    break;
                }
            }
        } catch (Exception ex) {
            log.warn("Agent dispatch unavailable: {}", ex.getClass().getSimpleName());
        }
    }

    private void process(Long id) {
        Claim claim = null;
        Future<NodeResult> future = null;
        long start = System.nanoTime();
        String outcome = "failed";
        try {
            claim = store.claim(id);
            if (claim == null) return;
            Claim active = claim;
            future = actions.submit(() -> graph.step(active));
            NodeResult result = future.get(properties.timeout(), TimeUnit.SECONDS);
            outcome =
                    store.checkpoint(
                                    claim,
                                    result,
                                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start))
                            ? "checkpointed"
                            : "discarded";
        } catch (Exception ex) {
            if (future != null) future.cancel(true);
            if (claim != null) {
                try {
                    store.fail(claim, ex instanceof TimeoutException);
                } catch (Exception failure) {
                    log.warn("Agent failure checkpoint unavailable: run={}", id);
                }
            }
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.debug("Agent node interrupted: run={},type={}", id, ex.getClass().getSimpleName());
        } finally {
            if (claim != null)
                meters.timer("studyflow.agent.node", "node", claim.stage(), "outcome", outcome)
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    @PreDestroy
    public void close() {
        workers.shutdownNow();
        actions.shutdownNow();
    }
}
