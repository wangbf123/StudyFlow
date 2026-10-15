package com.studyflow.workspace;

import cn.dev33.satoken.stp.StpUtil;
import com.studyflow.common.Result;
import com.studyflow.task.TaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/knowledge-bases/{knowledgeBaseId}")
@RequiredArgsConstructor
public class WorkspaceController {
    private final WorkspaceService workspace;

    @GetMapping("/overview")
    public Result<KnowledgeOverview> overview(@PathVariable Long knowledgeBaseId) {
        return Result.ok(workspace.overview(StpUtil.getLoginIdAsLong(),knowledgeBaseId));
    }
    @GetMapping("/document-page")
    public Result<DocumentPage> documents(@PathVariable Long knowledgeBaseId, @RequestParam(required=false) Long beforeId,
                                          @RequestParam(defaultValue="20") int size, @RequestParam(required=false) String keyword,
                                          @RequestParam(required=false) String status) {
        return Result.ok(workspace.documents(StpUtil.getLoginIdAsLong(),knowledgeBaseId,beforeId,size,keyword,status));
    }
    @GetMapping("/tasks")
    public Result<List<TaskResponse>> tasks(@PathVariable Long knowledgeBaseId) {
        return Result.ok(workspace.recentTasks(StpUtil.getLoginIdAsLong(),knowledgeBaseId));
    }
}
