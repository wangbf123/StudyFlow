package com.studyflow.mq;

import com.studyflow.task.DocumentParseService;
import lombok.RequiredArgsConstructor;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "studyflow.mq", name = "mode", havingValue = "rocketmq")
@RocketMQMessageListener(
        topic = "${studyflow.mq.parse-topic}",
        consumerGroup = "${rocketmq.consumer.group}"
)
public class ParseTaskListener implements RocketMQListener<Long> {

    private final DocumentParseService documentParseService;

    @Override
    public void onMessage(Long taskId) {
        documentParseService.parse(taskId);
    }
}
