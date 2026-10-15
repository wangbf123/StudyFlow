package com.studyflow.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.JdbcTemplate;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final ParseTaskMapper parseTaskMapper;
    private final PipelineStore pipeline;
    private final JdbcTemplate jdbc;

    public TaskResponse get(Long userId, Long taskId) {
        return TaskResponse.from(getOwnedTask(userId, taskId));
    }

    @Transactional
    public TaskResponse retry(Long userId, Long taskId) {
        ParseTaskEntity task = getOwnedTask(userId, taskId);
        if (!TaskStatus.FAILED.equals(task.getStatus()) && !TaskStatus.RETRYING.equals(task.getStatus())) {
            throw new BusinessException("只有失败或待重试任务可以手动重试");
        }
        if (jdbc.queryForList("SELECT id FROM document WHERE id=? AND user_id=? FOR UPDATE", Long.class,
                task.getDocumentId(),userId).isEmpty()) throw new BusinessException("文档不存在或无权限访问");
        int changed = jdbc.update("""
                UPDATE parse_task t JOIN document d ON d.id=t.document_id
                JOIN knowledge_base k ON k.id=d.knowledge_base_id
                SET t.status='PENDING',t.retry_count=0,t.error_message=NULL,t.claim_token=NULL,t.lease_until=NULL,
                  t.started_time=NULL,t.finished_time=NULL,t.next_attempt_time=NOW(6),t.update_time=NOW()
                WHERE t.id=? AND t.user_id=? AND t.status IN ('FAILED','RETRYING') AND t.generation=d.generation
                  AND ((t.stage='CLEANUP' AND d.parse_status='DELETED') OR
                    (t.stage<>'CLEANUP' AND d.parse_status<>'DELETED' AND k.status='ACTIVE'))
                """, taskId, userId);
        if (changed == 0) throw new BusinessException("任务已失效或正在处理中");
        if ("SUMMARY".equals(task.getStage())) jdbc.update("UPDATE document SET summary_status='PENDING' WHERE id=?",task.getDocumentId());
        pipeline.enqueue(taskId, task.getStage());
        return TaskResponse.from(parseTaskMapper.selectById(taskId));
    }

    private ParseTaskEntity getOwnedTask(Long userId, Long taskId) {
        ParseTaskEntity task = parseTaskMapper.selectOne(Wrappers.<ParseTaskEntity>lambdaQuery()
                .eq(ParseTaskEntity::getId, taskId)
                .eq(ParseTaskEntity::getUserId, userId));
        if (task == null) {
            throw new BusinessException("解析任务不存在或无权限访问");
        }
        return task;
    }
}
