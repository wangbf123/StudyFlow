package com.studyflow.mq;

import com.studyflow.task.TaskPublisher;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "studyflow.mq", name = "mode", havingValue = "rocketmq", matchIfMissing = true)
public class RocketMqTaskPublisher implements TaskPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${studyflow.mq.parse-topic}")
    private String parseTopic;

    @Override
    public void publishParseTask(Long taskId) {
        SendResult result = rocketMQTemplate.syncSend(parseTopic, taskId, 10000);
        if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
            throw new IllegalStateException("RocketMQ did not confirm SEND_OK: " + (result == null ? "no result" : result.getSendStatus()));
        }
    }
}
