package com.studyflow.kb;

import cn.dev33.satoken.stp.StpUtil;
import com.studyflow.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping
    public Result<KnowledgeBaseResponse> create(@Valid @RequestBody KnowledgeBaseCreateRequest request) {
        return Result.ok(knowledgeBaseService.create(StpUtil.getLoginIdAsLong(), request));
    }

    @GetMapping
    public Result<List<KnowledgeBaseResponse>> list() {
        return Result.ok(knowledgeBaseService.list(StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/{id}")
    public Result<KnowledgeBaseResponse> get(@PathVariable Long id) {
        return Result.ok(knowledgeBaseService.get(StpUtil.getLoginIdAsLong(), id));
    }

    @PutMapping("/{id}")
    public Result<KnowledgeBaseResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody KnowledgeBaseUpdateRequest request
    ) {
        return Result.ok(knowledgeBaseService.update(StpUtil.getLoginIdAsLong(), id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        knowledgeBaseService.delete(StpUtil.getLoginIdAsLong(), id);
        return Result.ok();
    }
}
