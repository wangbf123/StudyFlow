package com.studyflow.mq;

import com.studyflow.task.DocumentParseService;
import com.studyflow.task.ParseTaskResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "studyflow.mq", name = "mode", havingValue = "rocketmq", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${studyflow.mq.parse-topic}",
        consumerGroup = "${rocketmq.consumer.group}"
)
public class ParseTaskListener implements RocketMQListener<Long> {

    private final DocumentParseService documentParseService;

    @Override
    public void onMessage(Long taskId) {
        ParseTaskResult result = documentParseService.parse(taskId);
        // Database next_attempt_time and outbox own retries; early broker redelivery is harmless.
        log.debug("Pipeline wakeup task={},status={}", taskId, result.status());
    }
}
