package com.studyflow.document;

import java.time.LocalDateTime;

public record DocumentResponse(
        Long id,
        Long knowledgeBaseId,
        String originalFileName,
        String fileType,
        Long fileSize,
        String fileMd5,
        String parseStatus,
        String parserType,
        Integer pageCount,
        Integer ocrPageCount,
        Long ocrElapsedMs,
        String summary,
        LocalDateTime createTime,
        Long generation,
        String summaryStatus,
        Integer summaryInputChars,
        Boolean summaryTruncated
) {
    public static DocumentResponse from(DocumentEntity entity) {
        return new DocumentResponse(
                entity.getId(),
                entity.getKnowledgeBaseId(),
                entity.getOriginalFileName(),
                entity.getFileType(),
                entity.getFileSize(),
                entity.getFileMd5(),
                entity.getParseStatus(),
                entity.getParserType(),
                entity.getPageCount(),
                entity.getOcrPageCount(),
                entity.getOcrElapsedMs(),
                entity.getSummary(),
                entity.getCreateTime(),
                entity.getGeneration(),
                entity.getSummaryStatus(),
                entity.getSummaryInputChars(),
                entity.getSummaryTruncated()
        );
    }
}
