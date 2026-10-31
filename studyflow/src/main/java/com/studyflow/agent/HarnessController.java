package com.studyflow.agent;

import cn.dev33.satoken.stp.StpUtil;

import com.studyflow.agent.context.*;
import com.studyflow.agent.memory.MemorySystem;
import com.studyflow.agent.model.ModelGateway;
import com.studyflow.agent.planning.TaskManager;
import com.studyflow.agent.runtime.DockerEnvironment;
import com.studyflow.agent.skills.SkillCatalog;
import com.studyflow.agent.state.AgentStateStore;
import com.studyflow.agent.tools.McpConnections;
import com.studyflow.common.Result;
import com.studyflow.notebook.NoteService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** User-owned management APIs. Endpoint credentials/URLs and raw prompts never leave the server. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/agent")
public class HarnessController {
    private final AgentStateStore store;
    private final MemorySystem memory;
    private final McpConnections mcp;
    private final SkillCatalog skills;
    private final ModelGateway models;
    private final DockerEnvironment environment;
    private final com.studyflow.agent.verification.AgentEvaluation evaluation;
    private final TaskManager tasks;
    private final NoteService notes;
    private final HarnessProperties config;
    private final JdbcTemplate jdbc;
    private final AgentJson json;

    @GetMapping("/architecture")
    public Result<Object> architecture() {
        return Result.ok(
                Map.of(
                        "version",
                        "3.0",
                        "layers",
                        List.of(
                                Map.of(
                                        "name",
                                        "模型与上下文",
                                        "modules",
                                        List.of(
                                                "Model Gateway",
                                                "Instructions & Skills",
                                                "Context Manager")),
                                Map.of(
                                        "name",
                                        "执行与编排",
                                        "modules",
                                        List.of(
                                                "Agent Harness",
                                                "Planner / Task Manager",
                                                "Tool Registry & Executor")),
                                Map.of(
                                        "name",
                                        "状态与运行环境",
                                        "modules",
                                        List.of(
                                                "Session & State Store",
                                                "Memory System",
                                                "Runtime & Environment")),
                                Map.of(
                                        "name",
                                        "可靠性与治理",
                                        "modules",
                                        List.of(
                                                "Security & Governance",
                                                "Verification & Evaluation",
                                                "Observability"))),
                        "skills",
                        skills.all(),
                        "models",
                        models.describe(),
                        "context",
                        Map.of(
                                "inputTokens",
                                config.inputs(),
                                "outputTokens",
                                config.outputs(),
                                "encoding",
                                "O200K_ESTIMATE",
                                "strategy",
                                "RECENCY_EXTRACTIVE_WITH_ARTIFACT_REFERENCES"),
                        "runtime",
                        Map.of(
                                "scheduler",
                                "MYSQL_LEASE_FENCING",
                                "graph",
                                "LangGraph4j 1.9.7",
                                "mcpSdk",
                                "2.1.0",
                                "sandboxEnabled",
                                environment.available(),
                                "maxTeamMembers",
                                Math.clamp(config.getMaxTeamMembers(), 1, 3),
                                "maxDepth",
                                1)));
    }

    @GetMapping("/memories")
    public Result<Object> memories(@RequestParam List<Long> knowledgeBaseIds) {
        Long user = StpUtil.getLoginIdAsLong();
        return Result.ok(memory.manage(user, notes.scope(user, knowledgeBaseIds)));
    }

    @PostMapping("/memories")
    public Result<Object> remember(@RequestBody MemorySystem.Write request) {
        return Result.ok(memory.put(StpUtil.getLoginIdAsLong(), request));
    }

    @DeleteMapping("/memories/{id}")
    public Result<Object> forget(@PathVariable Long id) {
        memory.forget(StpUtil.getLoginIdAsLong(), id);
        return Result.ok(Map.of("forgotten", true));
    }

    @GetMapping("/connections")
    public Result<Object> connections() {
        return Result.ok(mcp.describe(StpUtil.getLoginIdAsLong()));
    }

    public record Binding(
            @NotEmpty @Size(max = 8) List<@NotNull Long> knowledgeBaseIds, boolean enabled) {}

    @PutMapping("/connections/{key}")
    public Result<Object> bind(@PathVariable String key, @Valid @RequestBody Binding request) {
        mcp.bind(StpUtil.getLoginIdAsLong(), key, request.knowledgeBaseIds(), request.enabled());
        return connections();
    }

    @GetMapping("/connections/{key}/resources")
    public Result<Object> resources(@PathVariable String key) {
        return Result.ok(mcp.resources(StpUtil.getLoginIdAsLong(), key));
    }

    public record Import(
            @NotBlank @Size(max = 2000) String uri,
            @NotNull Long knowledgeBaseId,
            @NotBlank @Size(max = 160) String title) {}

    @PostMapping("/connections/{key}/import")
    public Result<Object> importResource(
            @PathVariable String key, @Valid @RequestBody Import request) {
        return Result.ok(
                mcp.importResource(
                        StpUtil.getLoginIdAsLong(),
                        key,
                        request.uri(),
                        request.knowledgeBaseId(),
                        request.title()));
    }

    @GetMapping("/runs/{id}/evaluation")
    public Result<Object> evaluation(@PathVariable Long id) {
        return Result.ok(evaluation.evaluate(StpUtil.getLoginIdAsLong(), id));
    }

    @GetMapping("/runs/{id}/diagnostics")
    public Result<Object> diagnostics(@PathVariable Long id) {
        Long user = StpUtil.getLoginIdAsLong();
        var run = store.get(user, id);
        var contexts =
                jdbc.query(
                        "SELECT invocation_no,purpose,manifest_json FROM agent_context_manifest"
                                + " WHERE run_id=? ORDER BY invocation_no LIMIT 100",
                        (rs, n) ->
                                Map.of(
                                        "invocation",
                                        rs.getInt(1),
                                        "purpose",
                                        rs.getString(2),
                                        "manifest",
                                        json.tree(rs.getString(3))),
                        id);
        var calls =
                jdbc.query(
                        "SELECT"
                            + " purpose,route_name,model_name,status,latency_ms,prompt_tokens,completion_tokens,trace_id"
                            + " FROM agent_model_invocation WHERE run_id=? ORDER BY id LIMIT 100",
                        (rs, n) -> {
                            Map<String, Object> row = new LinkedHashMap<>();
                            row.put("purpose", rs.getString(1));
                            row.put("route", rs.getString(2));
                            row.put("model", rs.getString(3));
                            row.put("status", rs.getString(4));
                            row.put("latencyMs", rs.getLong(5));
                            row.put("inputTokens", rs.getObject(6));
                            row.put("outputTokens", rs.getObject(7));
                            row.put("traceId", rs.getString(8));
                            return row;
                        },
                        id);
        var team = store.children(user, id);
        var budget =
                jdbc.queryForMap(
                        "SELECT root_model_calls AS calls,root_token_charge AS"
                            + " chargedTokens,root_reserved_tokens AS"
                            + " reservedTokens,root_prompt_tokens AS"
                            + " reportedInputTokens,root_completion_tokens AS reportedOutputTokens"
                            + " FROM agent_run WHERE id=?",
                        run.rootRunId());
        return Result.ok(
                Map.of(
                        "plan",
                        tasks.list(id),
                        "children",
                        team,
                        "contexts",
                        contexts,
                        "modelCalls",
                        calls,
                        "sharedBudget",
                        budget,
                        "planCompletionMode",
                        "AGENT_ASSESSED"));
    }
}
