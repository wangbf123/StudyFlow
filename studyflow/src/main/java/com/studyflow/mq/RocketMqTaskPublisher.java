package com.studyflow.mq;

import com.studyflow.task.TaskPublisher;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "studyflow.mq", name = "mode", havingValue = "rocketmq")
public class RocketMqTaskPublisher implements TaskPublisher {

    private final RocketMQTemplate rocketMQTemplate;

    @Value("${studyflow.mq.parse-topic}")
    private String parseTopic;

    @Override
    public void publishParseTask(Long taskId) {
        rocketMQTemplate.convertAndSend(parseTopic, taskId);
    }
}
