package com.studyflow.qa;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("qa_claim")
public class QaClaimEntity {
    @TableId private Long id;
    private Long messageId;
    private Integer ordinal;
    private String text;
    private String verificationStatus;
    @TableField(fill = FieldFill.INSERT) private LocalDateTime createTime;
}
