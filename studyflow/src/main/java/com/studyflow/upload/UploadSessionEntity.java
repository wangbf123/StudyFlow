package com.studyflow.upload;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("upload_session")
public class UploadSessionEntity {

    @TableId
    private Long id;
    private String uploadId;
    private Long userId;
    private Long knowledgeBaseId;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private String fileMd5;
    private Long chunkSize;
    private Integer totalParts;
    private String status;
    private String finalBucket;
    private String finalObjectKey;
    private Long documentId;
    private Long taskId;
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
