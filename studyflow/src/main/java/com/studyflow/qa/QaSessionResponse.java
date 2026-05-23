package com.studyflow.qa;

import java.time.LocalDateTime;

public record QaSessionResponse(
        Long id,
        Long knowledgeBaseId,
        String title,
        String status,
        LocalDateTime createTime,
        LocalDateTime updateTime
) {
    public static QaSessionResponse from(QaSessionEntity entity) {
        return new QaSessionResponse(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getCreateTime(),
                entity.getUpdateTime()
        );
    }
}
