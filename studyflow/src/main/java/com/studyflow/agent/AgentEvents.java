package com.studyflow.agent;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.state.AgentStateStore;
import com.studyflow.common.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.ConcurrentHashMap;

/** SSE is a replayable view over committed events. Disconnect never cancels the durable run. */
@Component
@RequiredArgsConstructor
public class AgentEvents {
    private final AgentStateStore store;
    private final ConcurrentHashMap<SseEmitter, Subscription> subscriptions =
            new ConcurrentHashMap<>();

    private static final class Subscription {
        final Long userId, runId;
        int cursor;

        Subscription(Long userId, Long runId, int cursor) {
            this.userId = userId;
            this.runId = runId;
            this.cursor = cursor;
        }
    }

    public synchronized SseEmitter subscribe(Long userId, Long runId, int after) {
        Run run = store.get(userId, runId);
        if (after < 0 || after > run.eventSeq()) throw new BusinessException(400, "事件游标超出范围");
        if (subscriptions.size() >= 128
                || subscriptions.values().stream().filter(s -> s.userId.equals(userId)).count()
                        >= 4) throw new BusinessException(429, "事件连接过多，请关闭重复页面后重试");
        var emitter = new SseEmitter(25_000L);
        subscriptions.put(emitter, new Subscription(userId, runId, after));
        emitter.onCompletion(() -> subscriptions.remove(emitter));
        emitter.onTimeout(() -> {
            subscriptions.remove(emitter);
            emitter.complete();
        });
        emitter.onError(ex -> subscriptions.remove(emitter));
        return emitter;
    }

    @Scheduled(fixedDelayString = "${studyflow.agent.event-delay-ms:800}")
    public void publish() {
        subscriptions.forEach((emitter, subscription) -> {
            try {
                for (Event event :
                        store.events(
                                subscription.userId, subscription.runId, subscription.cursor)) {
                    emitter.send(
                            SseEmitter.event()
                                    .id(Integer.toString(event.seq()))
                                    .name("agent_event")
                                    .data(event));
                    subscription.cursor = event.seq();
                }
                Run run = store.get(subscription.userId, subscription.runId);
                if (run.terminal() && subscription.cursor >= run.eventSeq()) {
                    emitter.send(SseEmitter.event().name("run_end").data(run));
                    subscriptions.remove(emitter);
                    emitter.complete();
                } else emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception ex) {
                subscriptions.remove(emitter);
                emitter.complete();
            }
        });
    }
}
