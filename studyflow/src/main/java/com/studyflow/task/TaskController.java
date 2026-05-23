package com.studyflow.task;

import cn.dev33.satoken.stp.StpUtil;
import com.studyflow.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/tasks")
public class TaskController {

    private final TaskService taskService;

    @GetMapping("/{taskId}")
    public Result<TaskResponse> get(@PathVariable Long taskId) {
        return Result.ok(taskService.get(StpUtil.getLoginIdAsLong(), taskId));
    }

    @PostMapping("/{taskId}/retry")
    public Result<TaskResponse> retry(@PathVariable Long taskId) {
        return Result.ok(taskService.retry(StpUtil.getLoginIdAsLong(), taskId));
    }
}
