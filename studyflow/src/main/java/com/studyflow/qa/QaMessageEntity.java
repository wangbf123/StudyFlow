package com.studyflow.qa;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("qa_message")
public class QaMessageEntity {

    @TableId
    private Long id;
    private Long sessionId;
    private Long userId;
    private Long knowledgeBaseId;
    private String role;
    private String status;
    private String content;
    private String modelName;
    private Integer promptTokens;
    private Integer completionTokens;
    private Long latencyMs;
    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
