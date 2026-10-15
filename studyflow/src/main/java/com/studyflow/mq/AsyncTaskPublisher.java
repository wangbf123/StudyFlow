package com.studyflow.mq;

import com.studyflow.task.DocumentParseService;
import com.studyflow.task.ParseTaskEntity;
import com.studyflow.task.ParseTaskMapper;
import com.studyflow.task.TaskPublisher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executor;

/** Queue rejection propagates to the durable dispatcher; summaries cannot occupy parse workers. */
@Component
@ConditionalOnProperty(prefix="studyflow.mq",name="mode",havingValue="async")
public class AsyncTaskPublisher implements TaskPublisher {
    private final DocumentParseService worker;
    private final ParseTaskMapper tasks;
    private final Executor primary;
    private final Executor summary;

    public AsyncTaskPublisher(DocumentParseService worker,ParseTaskMapper tasks,
                              @Qualifier("parseTaskExecutor") Executor primary,
                              @Qualifier("summaryTaskExecutor") Executor summary) {
        this.worker=worker;this.tasks=tasks;this.primary=primary;this.summary=summary;
    }
    @Override
    public void publishParseTask(Long taskId) {
        ParseTaskEntity task=tasks.selectById(taskId);
        if(task==null) return;
        Executor executor="SUMMARY".equals(task.getStage()) ? summary : primary;
        executor.execute(()->worker.parse(taskId));
    }
}
