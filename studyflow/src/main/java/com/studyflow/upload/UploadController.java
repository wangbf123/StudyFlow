package com.studyflow.upload;

import cn.dev33.satoken.stp.StpUtil;
import com.studyflow.common.Result;
import com.studyflow.document.DocumentUploadResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/uploads")
public class UploadController {

    private final UploadService uploadService;

    @PostMapping("/init")
    public Result<UploadInitResponse> init(@Valid @RequestBody UploadInitRequest request) {
        return Result.ok(uploadService.init(StpUtil.getLoginIdAsLong(), request));
    }

    @PostMapping("/{uploadId}/parts")
    public Result<UploadPartResponse> uploadPart(
            @PathVariable String uploadId,
            @RequestParam Integer partNumber,
            @RequestParam(required = false) String partMd5,
            @RequestParam MultipartFile file
    ) {
        return Result.ok(uploadService.uploadPart(
                StpUtil.getLoginIdAsLong(),
                uploadId,
                partNumber,
                partMd5,
                file
        ));
    }

    @GetMapping("/{uploadId}/parts")
    public Result<UploadProgressResponse> progress(@PathVariable String uploadId) {
        return Result.ok(uploadService.progress(StpUtil.getLoginIdAsLong(), uploadId));
    }

    @PostMapping("/{uploadId}/complete")
    public Result<DocumentUploadResponse> complete(@PathVariable String uploadId) {
        return Result.ok(uploadService.complete(StpUtil.getLoginIdAsLong(), uploadId));
    }
}
