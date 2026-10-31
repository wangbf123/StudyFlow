package com.studyflow.agent.tools;

import static com.studyflow.agent.AgentTypes.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.*;
import com.studyflow.agent.*;
import com.studyflow.agent.governance.AgentPolicy;
import com.studyflow.agent.memory.MemorySystem;
import com.studyflow.agent.runtime.DockerEnvironment;
import com.studyflow.agent.skills.SkillCatalog;
import com.studyflow.agent.state.ToolResultStore;
import com.studyflow.ai.AiToolRequest.Definition;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class ToolRegistry {
    private final KnowledgeTools knowledge;
    private final McpConnections mcp;
    private final MemorySystem memory;
    private final ToolResultStore artifacts;
    private final DockerEnvironment environment;
    private final AgentPolicy policy;
    private final com.studyflow.agent.planning.TaskManager tasks;
    private final SkillCatalog skills;
    private final AgentJson json;

    public List<Definition> catalog(Claim run) {
        var all = new ArrayList<>(knowledge.definitions());
        all.add(
                new Definition(
                        "search_memory",
                        "Retrieve scoped, current memories. They are historical hints; re-read"
                                + " evidence for factual claims.",
                        json.tree(
                                "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\",\"maxLength\":200}},\"additionalProperties\":false}")));
        all.add(
                new Definition(
                        "read_tool_result",
                        "Read a bounded page of an earlier full tool result by stepNo.",
                        json.tree(
                                "{\"type\":\"object\",\"properties\":{\"stepNo\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100},\"start\":{\"type\":\"integer\",\"minimum\":0},\"size\":{\"type\":\"integer\",\"minimum\":100,\"maximum\":6000}},\"required\":[\"stepNo\"],\"additionalProperties\":false}")));
        all.add(
                new Definition(
                        "complete_plan_step",
                        "Record an agent-assessed plan step completion, backed by an existing"
                                + " successful tool observation. Dependencies must be complete.",
                        json.tree(
                                "{\"type\":\"object\",\"properties\":{\"stepNo\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":5},\"evidenceStep\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100}},\"required\":[\"stepNo\",\"evidenceStep\"],\"additionalProperties\":false}")));
        if (environment.available())
            all.add(
                    new Definition(
                            "run_python",
                            "Run Python in an ephemeral offline container; no host files are"
                                    + " mounted.",
                            json.tree(
                                    "{\"type\":\"object\",\"properties\":{\"code\":{\"type\":\"string\",\"maxLength\":16000}},\"required\":[\"code\"],\"additionalProperties\":false}")));
        all.removeIf(t -> !skills.get(run.state().skill()).tools().contains(t.name()));
        all.addAll(mcp.definitions(run).values());
        all.removeIf(t -> {
            try {
                safeSchema(t.parameters(), 0);
                return false;
            } catch (IllegalArgumentException ex) {
                return true;
            }
        });
        return List.copyOf(all);
    }

    private void safeSchema(JsonNode node, int depth) {
        if (depth > 32) throw new IllegalArgumentException("Schema nesting too deep");
        if (node.isObject()) {
            node.fields()
                    .forEachRemaining(e -> {
                        if (e.getKey().equals("$schema")
                                && !Set.of(
                                                "https://json-schema.org/draft/2020-12/schema",
                                                "http://json-schema.org/draft-07/schema#")
                                        .contains(e.getValue().asText()))
                            throw new IllegalArgumentException("Unknown schema dialect");
                        if (Set.of("$ref", "$dynamicRef").contains(e.getKey())
                                && !e.getValue().asText().startsWith("#"))
                            throw new IllegalArgumentException(
                                    "Remote schema references are disabled");
                        safeSchema(e.getValue(), depth + 1);
                    });
        } else if (node.isArray()) node.forEach(v -> safeSchema(v, depth + 1));
    }

    public void validate(Claim run, String name, JsonNode args) {
        var def =
                catalog(run).stream()
                        .filter(t -> t.name().equals(name))
                        .findFirst()
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Tool is not authorized for this run"));
        if (args == null || args.toString().length() > 20000)
            throw new IllegalArgumentException("Tool arguments too large");
        safeSchema(def.parameters(), 0);
        if (!SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                .getSchema(def.parameters())
                .validate(args)
                .isEmpty()) throw new IllegalArgumentException("Tool argument schema mismatch");
    }

    public KnowledgeTools.Output execute(Claim run, PendingCall call) {
        policy.check(run);
        validate(run, call.tool(), call.arguments());
        var args = call.arguments();
        return switch (call.tool()) {
            case "complete_plan_step" ->
                    new KnowledgeTools.Output(
                            json.write(
                                    tasks.complete(
                                            run,
                                            args.path("stepNo").asInt(),
                                            args.path("evidenceStep").asInt())),
                            List.of());
            case "search_memory" ->
                    new KnowledgeTools.Output(
                            json.write(
                                    memory.retrieve(
                                            run.userId(),
                                            run.state().scope(run.knowledgeBaseId()),
                                            args.path("query").asText(""),
                                            5)),
                            List.of());
            case "read_tool_result" ->
                    new KnowledgeTools.Output(
                            json.write(
                                    artifacts.read(
                                            run,
                                            args.path("stepNo").asInt(),
                                            args.path("start").asInt(0),
                                            args.path("size").asInt(2000))),
                            List.of());
            case "run_python" ->
                    new KnowledgeTools.Output(
                            json.write(environment.python(args.path("code").asText())), List.of());
            default ->
                    call.tool().startsWith("mcp__")
                            ? new KnowledgeTools.Output(mcp.call(run, call.tool(), args), List.of())
                            : knowledge.execute(run, call);
        };
    }
}
