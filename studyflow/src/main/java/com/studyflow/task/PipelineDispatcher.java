package com.studyflow.task;

import lombok.RequiredArgsConstructor;
import com.studyflow.search.SearchService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineDispatcher {
    private final PipelineStore store;
    private final TaskPublisher publisher;
    private final SearchService search;

    @Scheduled(fixedDelayString = "${studyflow.pipeline.scheduler-delay-ms:1000}", initialDelayString = "${studyflow.pipeline.scheduler-initial-delay-ms:3000}")
    public void dispatch() {
        try {
            store.recover();
            for (Long id : store.dueOutbox()) {
                String token = UUID.randomUUID().toString();
                Long taskId = store.claimOutbox(id, token);
                if (taskId == null) continue;
                try {
                    publisher.publishParseTask(taskId);
                    store.sent(id, token);
                } catch (Exception ex) {
                    store.sendFailed(id, token, ex);
                    log.warn("Pipeline outbox delivery failed: event={},task={}", id, taskId);
                }
            }
        } catch (Exception ex) {
            log.warn("Pipeline recovery/dispatch temporarily unavailable: {}", ex.getMessage());
        }
    }

    @Scheduled(fixedDelayString = "${studyflow.pipeline.index-reconcile-delay-ms:30000}", initialDelayString = "${studyflow.pipeline.index-reconcile-delay-ms:30000}")
    public void reconcileRetiredIndexes() {
        try {
            for (PipelineStore.RetiredIndex index : store.retiredIndexes()) {
                search.deleteDocumentGeneration(index.documentId(), index.generation());
                store.reconciled(index);
            }
        } catch (Exception ex) {
            log.warn("Retired index reconciliation will retry: {}", ex.getMessage());
        }
    }

    @Configuration
    @EnableScheduling
    static class SchedulingConfiguration {}
}
