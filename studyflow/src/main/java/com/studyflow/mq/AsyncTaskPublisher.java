package com.studyflow.mq;

import com.studyflow.task.TaskPublisher;
import com.studyflow.task.DocumentParseService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "studyflow.mq", name = "mode", havingValue = "async")
public class AsyncTaskPublisher implements TaskPublisher {

    private final DocumentParseService documentParseService;

    @Async("parseTaskExecutor")
    @Override
    public void publishParseTask(Long taskId) {
        documentParseService.parse(taskId);
    }
}
