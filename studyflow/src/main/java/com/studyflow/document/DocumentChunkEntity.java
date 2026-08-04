package com.studyflow.document;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document_chunk")
public class DocumentChunkEntity {

    @TableId
    private Long id;
    private Long documentId;
    private Long knowledgeBaseId;
    private Long userId;
    private Integer chunkIndex;
    private String content;
    private Integer charCount;
    private Integer pageNumber;
    private String parserType;
    private Double ocrConfidence;
    private String embedding;
    private String embeddingModel;
    private Integer embeddingDim;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
