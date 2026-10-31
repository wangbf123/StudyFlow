package com.studyflow.kb;

import java.time.LocalDateTime;

public record KnowledgeBaseResponse(
        Long id,
        String name,
        String description,
        String status,
        LocalDateTime createTime,
        Long parentId,
        String templateKey,
        String color,
        LocalDateTime updateTime
) {
    public static KnowledgeBaseResponse from(KnowledgeBaseEntity entity) {
        return new KnowledgeBaseResponse(
                entity.getId(),
                entity.getName(),
                entity.getDescription(),
                entity.getStatus(),
                entity.getCreateTime(),
                entity.getParentId(),
                entity.getTemplateKey(),
                entity.getColor(),
                entity.getUpdateTime()
        );
    }
}
