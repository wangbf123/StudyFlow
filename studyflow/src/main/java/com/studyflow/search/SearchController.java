package com.studyflow.search;

import cn.dev33.satoken.stp.StpUtil;
import com.studyflow.common.Result;
import com.studyflow.kb.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/search")
public class SearchController {

    private final SearchService searchService;
    private final KnowledgeBaseService knowledgeBaseService;

    @GetMapping("/explain")
    public Result<SearchExplanation> explain(@RequestParam Long knowledgeBaseId,@RequestParam String keyword,
            @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="10") int size,
            @RequestParam(required=false) SearchProperties.RetrievalMode mode) {
        Long userId=StpUtil.getLoginIdAsLong();
        knowledgeBaseService.getOwnedActive(userId,knowledgeBaseId);
        return Result.ok(searchService.explainSearch(userId,knowledgeBaseId,keyword,page,size,mode));
    }

    @GetMapping
    public Result<List<SearchResponse>> search(
            @RequestParam Long knowledgeBaseId,
            @RequestParam String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) SearchProperties.RetrievalMode mode
    ) {
        Long userId = StpUtil.getLoginIdAsLong();
        knowledgeBaseService.getOwnedActive(userId, knowledgeBaseId);
        return Result.ok(mode == null ? searchService.search(userId, knowledgeBaseId, keyword, page, size)
                : searchService.searchChunks(userId, knowledgeBaseId, keyword, page, size, mode).stream()
                .map(SearchResponse::from).toList());
    }
}
