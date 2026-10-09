package com.studyflow.task;

import java.time.LocalDateTime;

public record TaskResponse(
        Long id,
        String taskNo,
        Long documentId,
        String status,
        String stage,
        Long generation,
        LocalDateTime nextAttemptTime,
        Integer retryCount,
        Integer maxRetryCount,
        String errorMessage,
        LocalDateTime startedTime,
        LocalDateTime finishedTime,
        LocalDateTime createTime
) {
    public static TaskResponse from(ParseTaskEntity entity) {
        return new TaskResponse(
                entity.getId(),
                entity.getTaskNo(),
                entity.getDocumentId(),
                entity.getStatus(),
                entity.getStage(),
                entity.getGeneration(),
                entity.getNextAttemptTime(),
                entity.getRetryCount(),
                entity.getMaxRetryCount(),
                entity.getErrorMessage(),
                entity.getStartedTime(),
                entity.getFinishedTime(),
                entity.getCreateTime()
        );
    }
}
