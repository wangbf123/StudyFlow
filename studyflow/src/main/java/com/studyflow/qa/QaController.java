package com.studyflow.qa;

import cn.dev33.satoken.stp.StpUtil;
import com.studyflow.common.Result;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/qa")
public class QaController {

    private final QaService qaService;
    private final QaEvalService qaEvalService;

    @PostMapping("/sessions")
    public Result<QaSessionResponse> createSession(@Valid @RequestBody QaCreateSessionRequest request) {
        return Result.ok(qaService.createSession(StpUtil.getLoginIdAsLong(), request));
    }

    @GetMapping("/sessions")
    public Result<List<QaSessionResponse>> listSessions(@RequestParam(required = false) Long knowledgeBaseId) {
        return Result.ok(qaService.listSessions(StpUtil.getLoginIdAsLong(), knowledgeBaseId));
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<QaMessageResponse>> listMessages(@PathVariable Long sessionId) {
        return Result.ok(qaService.listMessages(StpUtil.getLoginIdAsLong(), sessionId));
    }

    @PostMapping("/retrieve")
    public Result<QaRetrieveResponse> retrieve(@Valid @RequestBody QaRetrieveRequest request) {
        return Result.ok(qaService.retrieve(StpUtil.getLoginIdAsLong(), request));
    }

    @GetMapping("/evaluate/samples")
    public Result<List<QaEvalQuestionRequest>> evaluationSamples() {
        return Result.ok(qaEvalService.samples());
    }

    @PostMapping("/evaluate")
    public Result<QaEvalResponse> evaluate(@Valid @RequestBody QaEvalRequest request) {
        return Result.ok(qaEvalService.evaluate(StpUtil.getLoginIdAsLong(), request));
    }

    @PostMapping("/ask")
    public Result<QaAskResponse> ask(@Valid @RequestBody QaAskRequest request) {
        return Result.ok(qaService.ask(StpUtil.getLoginIdAsLong(), request));
    }

    @PostMapping(value = "/ask/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter askStream(@Valid @RequestBody QaAskRequest request) {
        return qaService.askStream(StpUtil.getLoginIdAsLong(), request);
    }

    @GetMapping("/messages/{messageId}/citations")
    public Result<List<QaCitationResponse>> listCitations(@PathVariable Long messageId) {
        return Result.ok(qaService.listCitations(StpUtil.getLoginIdAsLong(), messageId));
    }

    @GetMapping("/citations/{citationId}/chunk")
    public Result<QaCitationDetailResponse> getCitationChunk(@PathVariable Long citationId) {
        return Result.ok(qaService.getCitationChunk(StpUtil.getLoginIdAsLong(), citationId));
    }
}
