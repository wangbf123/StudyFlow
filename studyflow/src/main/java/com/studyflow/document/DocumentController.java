package com.studyflow.document;

import cn.dev33.satoken.stp.StpUtil;
import com.studyflow.common.Result;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api")
public class DocumentController {

    private final DocumentService documentService;

    @PostMapping("/documents/upload")
    public Result<DocumentUploadResponse> upload(
            @RequestParam Long knowledgeBaseId,
            @RequestParam MultipartFile file
    ) {
        return Result.ok(documentService.upload(StpUtil.getLoginIdAsLong(), knowledgeBaseId, file));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public Result<List<DocumentResponse>> listByKnowledgeBase(@PathVariable Long knowledgeBaseId) {
        return Result.ok(documentService.listByKnowledgeBase(StpUtil.getLoginIdAsLong(), knowledgeBaseId));
    }

    @GetMapping("/documents/{documentId}")
    public Result<DocumentResponse> get(@PathVariable Long documentId) {
        return Result.ok(documentService.get(StpUtil.getLoginIdAsLong(), documentId));
    }

    @GetMapping("/documents/{documentId}/chunks")
    public Result<List<DocumentChunkResponse>> chunks(@PathVariable Long documentId) {
        return Result.ok(documentService.chunks(StpUtil.getLoginIdAsLong(), documentId));
    }
}
