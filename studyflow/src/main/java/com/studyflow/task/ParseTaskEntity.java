package com.studyflow.task;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("parse_task")
public class ParseTaskEntity {

    @TableId
    private Long id;
    private String taskNo;
    private Long documentId;
    private Long userId;
    private String status;
    private Integer retryCount;
    private Integer maxRetryCount;
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
