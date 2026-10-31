package com.studyflow.agent;

import static com.studyflow.agent.AgentTypes.*;

import cn.dev33.satoken.stp.StpUtil;

import com.studyflow.agent.state.AgentStateStore;
import com.studyflow.agent.tools.KnowledgeTools;
import com.studyflow.ai.AiProperties;
import com.studyflow.common.BusinessException;
import com.studyflow.common.Result;

import jakarta.validation.Valid;

import lombok.RequiredArgsConstructor;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentStateStore store;
    private final AgentEvents events;
    private final KnowledgeTools tools;
    private final AgentProperties properties;
    private final AiProperties ai;
    private final com.studyflow.notebook.NoteService notes;

    @PostMapping("/runs")
    public ResponseEntity<Result<Run>> create(@Valid @RequestBody CreateRequest request) {
        return ResponseEntity.accepted()
                .body(Result.ok(store.create(StpUtil.getLoginIdAsLong(), request)));
    }

    @GetMapping("/runs/{id}")
    public Result<Run> get(@PathVariable Long id) {
        return Result.ok(store.get(StpUtil.getLoginIdAsLong(), id));
    }

    @PostMapping("/runs/{id}/cancel")
    public Result<Run> cancel(@PathVariable Long id) {
        return Result.ok(store.cancel(StpUtil.getLoginIdAsLong(), id));
    }

    @PostMapping("/runs/{id}/retry")
    public ResponseEntity<Result<Run>> retry(
            @PathVariable Long id, @Valid @RequestBody RetryRequest request) {
        return ResponseEntity.accepted()
                .body(Result.ok(store.retry(StpUtil.getLoginIdAsLong(), id, request)));
    }

    @GetMapping("/sessions")
    public Result<List<Session>> sessions(@RequestParam Long knowledgeBaseId) {
        return Result.ok(store.sessions(StpUtil.getLoginIdAsLong(), knowledgeBaseId));
    }

    @GetMapping("/sessions/{id}/runs")
    public Result<List<Run>> runs(@PathVariable Long id) {
        return Result.ok(store.runs(StpUtil.getLoginIdAsLong(), id));
    }

    @GetMapping("/runs/{id}/events")
    public Result<List<Event>> events(
            @PathVariable Long id, @RequestParam(defaultValue = "0") int after) {
        return Result.ok(store.events(StpUtil.getLoginIdAsLong(), id, after));
    }

    @GetMapping(value = "/runs/{id}/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int after,
            @RequestHeader(value = "Last-Event-ID", required = false) Integer lastEventId) {
        return events.subscribe(
                StpUtil.getLoginIdAsLong(), id, lastEventId == null ? after : lastEventId);
    }

    /**
     * Compatibility route: starts a durable run, then subscribes. Prefer separate create and
     * subscribe.
     */
    @PostMapping(value = "/runs/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter createStream(@Valid @RequestBody CreateRequest request) {
        Long user = StpUtil.getLoginIdAsLong();
        Run run = store.create(user, request);
        return events.subscribe(user, run.id(), 0);
    }

    public record SaveArtifact(
            @jakarta.validation.constraints.NotNull Long knowledgeBaseId,
            @jakarta.validation.constraints.Size(max = 160) String title) {}

    @PostMapping("/runs/{id}/save-note")
    public Result<com.studyflow.notebook.NoteTypes.Note> saveNote(
            @PathVariable Long id, @Valid @RequestBody SaveArtifact request) {
        Long user = StpUtil.getLoginIdAsLong();
        Run run = store.get(user, id);
        if (run.artifact() == null) throw new BusinessException(409, "报告尚未生成");
        if (!run.knowledgeBaseIds().contains(request.knowledgeBaseId()))
            throw new BusinessException(400, "成果只能写入本次选定的知识库");
        String title =
                request.title() == null || request.title().isBlank()
                        ? com.studyflow.agent.AgentJson.clip(run.artifact().title(), 48)
                        : request.title();
        if (title.length() > 160) title = title.substring(0, 160);
        String markdown = run.artifact().markdown();
        if (!run.artifact().evidenceCurrent()) markdown = "> 来源已变化，请重新核验。\n\n" + markdown;
        return Result.ok(notes.fromArtifact(user, request.knowledgeBaseId(), title, markdown, id));
    }

    @GetMapping("/capabilities")
    public Result<Map<String, Object>> capabilities() {
        return Result.ok(
                Map.of(
                        "framework",
                        "LangGraph4j 1.9.7",
                        "runtimeVersion",
                        2,
                        "provider",
                        ai.getProvider(),
                        "model",
                        ai.getModel(),
                        "enabled",
                        properties.isEnabled(),
                        "tools",
                        tools.definitions(),
                        "budget",
                        new Budget(
                                properties.steps(),
                                properties.calls(),
                                properties.tokens(),
                                properties.deadlineSeconds())));
    }

    @GetMapping(value = "/runs/{id}/artifact", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> artifact(@PathVariable Long id) {
        Run run = store.get(StpUtil.getLoginIdAsLong(), id);
        if (run.artifact() == null) throw new BusinessException(409, "报告尚未生成");
        String content = run.artifact().markdown();
        if (!run.artifact().evidenceCurrent())
            content = "> 注意：来源资料已变化或此为旧版未核验报告，请重新执行任务。\n\n" + content;
        return ResponseEntity.ok()
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=studyflow-report-" + id + ".md")
                .body(content);
    }
}
