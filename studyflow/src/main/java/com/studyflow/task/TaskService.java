package com.studyflow.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.common.BusinessException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TaskService {

    private final ParseTaskMapper parseTaskMapper;
    private final TaskPublisher taskPublisher;

    public TaskResponse get(Long userId, Long taskId) {
        return TaskResponse.from(getOwnedTask(userId, taskId));
    }

    @Transactional
    public TaskResponse retry(Long userId, Long taskId) {
        ParseTaskEntity task = getOwnedTask(userId, taskId);
        if (!TaskStatus.FAILED.equals(task.getStatus()) && !TaskStatus.RETRYING.equals(task.getStatus())) {
            throw new BusinessException("只有失败或待重试任务可以手动重试");
        }
        task.setStatus(TaskStatus.PENDING);
        task.setErrorMessage(null);
        task.setStartedTime(null);
        task.setFinishedTime(null);
        parseTaskMapper.updateById(task);
        taskPublisher.publishParseTask(task.getId());
        return TaskResponse.from(task);
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
