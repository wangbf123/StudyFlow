package com.studyflow.document;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("document")
public class DocumentEntity {

    @TableId
    private Long id;
    private Long userId;
    private Long knowledgeBaseId;
    private String originalFileName;
    private String fileType;
    private Long fileSize;
    private String fileMd5;
    private String bucket;
    private String objectKey;
    private String parseStatus;
    private Long generation;
    private String parserType;
    private Integer pageCount;
    private Integer ocrPageCount;
    private Long ocrElapsedMs;
    private String summary;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
