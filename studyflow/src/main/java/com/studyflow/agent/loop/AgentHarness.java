package com.studyflow.agent.loop;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.*;
import com.studyflow.agent.context.ContextManager;
import com.studyflow.agent.model.ModelGateway;
import com.studyflow.agent.planning.*;
import com.studyflow.agent.state.AgentStateStore;
import com.studyflow.agent.tools.ToolRegistry;
import com.studyflow.agent.verification.AgentEvidenceAccess;
import com.studyflow.ai.*;
import com.studyflow.rag.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.*;

/** Harness decides transitions. Runtime schedules nodes; environments execute tools. */
@Component
@RequiredArgsConstructor
public class AgentHarness {
    private final AgentProperties properties;
    private final HarnessProperties config;
    private final ToolRegistry tools;
    private final AgentJson json;
    private final ContextManager contexts;
    private final ModelGateway gateway;
    private final TaskPlanner planner;
    private final MultiAgentCoordinator team;
    private final RagPromptBuilder prompts;
    private final EvidenceAnswerService evidence;
    private final AgentEvidenceAccess evidenceAccess;

    public NodeResult execute(String node, Claim run) {
        if (run.state().version() != 2)
            throw new IllegalStateException("Unsupported checkpoint version");
        return switch (node) {
            case "PLAN" -> plan(run);
            case "TEAM_DISPATCH" -> team.dispatch(run);
            case "WAIT_CHILDREN" -> team.collect(run);
            case "DECIDE" -> decide(run);
            case "TOOL" -> tool(run);
            case "SYNTHESIZE" -> synthesize(run);
            case "VERIFY" -> verify(run);
            default -> throw new IllegalArgumentException("Unknown graph node");
        };
    }

    private NodeResult plan(Claim run) {
        var plan = planner.plan(run);
        String next =
                Boolean.TRUE.equals(run.state().collaboration()) && run.depth() == 0
                        ? "TEAM_DISPATCH"
                        : "DECIDE";
        return new NodeResult(
                run.state().withPlan(plan), next, "plan_created", Map.of("steps", plan), null);
    }

    private NodeResult decide(Claim run) {
        var state = run.state();
        if (state.observations().size() >= state.budget().maxSteps()
                || state.decisions() >= state.budget().maxSteps() + 2)
            return new NodeResult(
                    state,
                    "SYNTHESIZE",
                    "research_completed",
                    Map.of("reason", "TOOL_BUDGET_REACHED"),
                    null);
        var context = contexts.assemble(run, "tool", tools.catalog(run));
        var response =
                gateway.tool(
                        run,
                        new AiToolRequest(
                                context.system()
                                        + "\n"
                                        + "Use exactly one provided function. Work through the"
                                        + " plan; avoid identical calls. Call finish_research when"
                                        + " ready. Completion of a plan step is an agent assessment"
                                        + " and needs a successful tool observation.",
                                context.user(),
                                run.goal(),
                                null,
                                context.tools()));
        try {
            // The model cannot call an authorized tool that was omitted from THIS invocation.
            if (context.tools().stream().noneMatch(t -> t.name().equals(response.name())))
                throw new IllegalArgumentException("Tool not offered");
            tools.validate(run, response.name(), response.arguments());
        } catch (IllegalArgumentException ex) {
            var observation =
                    new Observation(
                            state.observations().size() + 1,
                            "validation",
                            "REJECTED",
                            null,
                            "工具调用未通过 schema 或权限校验，请选择本轮提供的工具。",
                            "参数或权限校验未通过");
            return new NodeResult(
                    state.withDecision(null).withTool(observation, state.sources()),
                    "DECIDE",
                    "tool_rejected",
                    observation,
                    null);
        }
        if ("finish_research".equals(response.name()))
            return new NodeResult(
                    state.withDecision(null),
                    "SYNTHESIZE",
                    "research_completed",
                    Map.of("reason", "MODEL_FINISHED"),
                    null);
        var pending = new PendingCall(response.name(), response.arguments());
        return new NodeResult(state.withDecision(pending), "TOOL", "tool_selected", pending, null);
    }

    private NodeResult tool(Claim run) {
        var state = run.state();
        var call = state.pendingCall();
        if (call == null) throw new IllegalStateException("Tool node has no pending call");
        String signature = json.canonical(call.arguments());
        boolean duplicate =
                state.observations().stream()
                        .anyMatch(o ->
                                o.tool().equals(call.tool())
                                        && o.arguments() != null
                                        && json.canonical(o.arguments()).equals(signature));
        List<Source> sources = state.sources();
        String output, status;
        if (duplicate) {
            status = "SKIPPED";
            output = "相同调用已经执行，请读取已有结果、翻页或结束研究。";
        } else
            try {
                var result = tools.execute(run, call);
                status = "SUCCESS";
                output = AgentJson.clip(result.content(), config.artifactChars());
                var combined = new LinkedHashMap<String, Source>();
                sources.forEach(
                        s -> combined.put(s.chunk().source() + ":" + s.chunk().chunkId(), s));
                for (var source : result.sources())
                    if (combined.size() < properties.sources())
                        combined.putIfAbsent(
                                source.chunk().source() + ":" + source.chunk().chunkId(), source);
                sources = List.copyOf(combined.values());
            } catch (AgentStateStore.LeaseLost ex) {
                throw ex;
            } catch (Exception ex) {
                status = "FAILED";
                output = "工具暂不可用或资料无权访问。可尝试其他查询；不要根据错误推断事实。";
            }
        var observation =
                new Observation(
                        state.observations().size() + 1,
                        call.tool(),
                        status,
                        call.arguments(),
                        output,
                        switch (status) {
                            case "SUCCESS" -> "已读取工具结果";
                            case "SKIPPED" -> "已阻止重复调用";
                            default -> "工具未返回可用资料";
                        });
        // Full bounded result lives in state/artifact storage, never in the public SSE payload.
        return new NodeResult(
                state.withTool(observation, sources),
                "DECIDE",
                "tool_completed",
                Map.of(
                        "number",
                        observation.number(),
                        "tool",
                        observation.tool(),
                        "status",
                        status,
                        "summary",
                        observation.summary(),
                        "resultRef",
                        "tool-result:" + run.id() + ":" + observation.number()),
                null);
    }

    private NodeResult synthesize(Claim run) {
        var prompt =
                prompts.build(
                        run.goal(), run.state().sources().stream().map(Source::chunk).toList());
        String raw = "{\"claims\":[]}";
        if (!prompt.includedChunks().isEmpty()) {
            raw =
                    gateway.chat(
                                    run,
                                    "synthesize",
                                    new AiChatRequest(
                                            prompt.systemPrompt(),
                                            prompt.userPrompt(),
                                            run.goal(),
                                            null,
                                            0.1))
                            .content();
            if (raw == null || raw.length() > 64000)
                throw new IllegalArgumentException("Oversized synthesis");
        }
        return new NodeResult(
                run.state().withDraft(raw, prompt.includedChunks()),
                "VERIFY",
                "draft_prepared",
                Map.of("sourceCount", prompt.includedChunks().size()),
                null);
    }

    private NodeResult verify(Claim run) {
        var answer =
                evidence.verifyScoped(
                        run.state().draft(),
                        new RagPrompt("", "", run.state().draftSources()),
                        span -> evidenceAccess.refresh(run, span),
                        gateway.extractive(run),
                        request -> gateway.chat(run, "verify", request));
        return new NodeResult(
                run.state(),
                "__END__",
                "evidence_checked",
                Map.of(
                        "status",
                        answer.status(),
                        "mode",
                        answer.mode(),
                        "acceptedClaims",
                        answer.claims().size(),
                        "rejectedClaims",
                        answer.rejectedClaims()),
                answer);
    }
}
