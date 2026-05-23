package com.studyflow.qa;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("qa_citation")
public class QaCitationEntity {

    @TableId
    private Long id;
    private Long messageId;
    private Long documentId;
    private Long chunkId;
    private Integer chunkIndex;
    private String documentName;
    private String contentPreview;
    private Double score;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
