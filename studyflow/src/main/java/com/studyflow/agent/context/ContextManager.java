package com.studyflow.agent.context;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.*;
import com.studyflow.agent.memory.MemorySystem;
import com.studyflow.agent.skills.SkillCatalog;
import com.studyflow.ai.AiToolRequest.Definition;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class ContextManager {
    private final TokenCounter tokens;
    private final HarnessProperties config;
    private final AgentJson json;
    private final MemorySystem memory;
    private final SkillCatalog skills;
    private final JdbcTemplate jdbc;

    public record Context(
            String system,
            String user,
            List<Definition> tools,
            int estimatedTokens,
            int compressedResults,
            List<Long> memoryIds) {}

    public Context assemble(Claim run, String purpose, List<Definition> catalog) {
        String system = skills.system(run.state().skill(), run.role());
        int max = config.inputs();
        List<Definition> selected = new ArrayList<>();
        int toolBudget = Math.min(2500, max / 3), used = 0;
        // Reserve the terminal action and result-reader before optional descriptions.
        var ordered = new ArrayList<>(catalog);
        ordered.sort(
                Comparator.comparingInt(t ->
                        Set.of("finish_research", "read_tool_result").contains(t.name()) ? 0 : 1));
        for (var tool : ordered) {
            int cost = tokens.count(json.write(tool));
            if (used + cost <= toolBudget) {
                selected.add(tool);
                used += cost;
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("goal", run.goal());
        body.put("skill", skills.get(run.state().skill()).instruction());
        body.put("plan", run.state().plan());
        body.put("knowledgeBaseIds", run.state().scope(run.knowledgeBaseId()));
        body.put(
                "contextNoteId",
                run.state().contextNoteId() == null ? "" : run.state().contextNoteId().toString());
        body.put(
                "remainingToolSteps",
                run.state().budget().maxSteps() - run.state().observations().size());
        body.put("history", List.of());
        body.put("memories", List.of());
        body.put("observations", List.of());
        int base = tokens.count(system) + used + tokens.count(json.write(body)) + 128;
        if (base > max)
            throw new IllegalArgumentException("Immutable task context exceeds token budget");
        var recalled =
                memory.retrieve(
                        run.userId(), run.state().scope(run.knowledgeBaseId()), run.goal(), 5);
        List<Map<String, Object>> memories = new ArrayList<>();
        for (var item : recalled) {
            var entry =
                    Map.<String, Object>of(
                            "id",
                            item.id(),
                            "kind",
                            item.kind(),
                            "content",
                            tokens.prefix(item.content(), 180),
                            "originRunId",
                            item.originRunId() == null ? "" : item.originRunId().toString(),
                            "sourceNoteId",
                            item.sourceNoteId() == null ? "" : item.sourceNoteId().toString());
            if (tokens.count(json.write(memories)) + tokens.count(json.write(entry))
                    < Math.min(1000, (max - base) / 4)) memories.add(entry);
        }
        body.put("memories", memories);
        body.put(
                "history",
                run.state().history().stream().limit(4).map(x -> tokens.prefix(x, 120)).toList());
        List<Map<String, Object>> observations = new ArrayList<>();
        int compressed = 0;
        for (var item : run.state().observations())
            observations.add(
                    new LinkedHashMap<>(
                            Map.of(
                                    "number",
                                    item.number(),
                                    "tool",
                                    item.tool(),
                                    "status",
                                    item.status(),
                                    "summary",
                                    item.summary() == null ? "" : item.summary(),
                                    "resultRef",
                                    "tool-result:" + run.id() + ":" + item.number(),
                                    "arguments",
                                    item.arguments() == null ? Map.of() : item.arguments())));
        body.put("observations", observations);
        int remaining = max - 128 - used - tokens.count(system) - tokens.count(json.write(body));
        var source = run.state().observations();
        for (int i = source.size() - 1; i >= 0; i--) {
            String content = source.get(i).content();
            int allowance =
                    Math.max(0, Math.min(remaining - 60, i >= source.size() - 2 ? 1200 : 160));
            String excerpt = tokens.prefix(content, allowance);
            observations.get(i).put("content", excerpt);
            if (excerpt.length() < content.length()) {
                compressed++;
                observations.get(i).put("compressed", true);
            }
            remaining -= tokens.count(excerpt) + 40;
        }
        String payload = json.write(body);
        int estimated = tokens.count(system) + used + tokens.count(payload) + 128;
        // JSON escaping and framing are accounted after serialization. Never truncate instructions
        // or the goal.
        if (estimated > max) {
            for (var item : observations) item.remove("content");
            body.put("history", List.of());
            body.put("memories", List.of());
            memories.clear();
            compressed = observations.size();
            payload = json.write(body);
            estimated = tokens.count(system) + used + tokens.count(payload) + 128;
        }
        if (estimated > max) throw new IllegalArgumentException("Context manifest exceeds budget");
        return new Context(
                system,
                payload,
                List.copyOf(selected),
                estimated,
                compressed,
                memories.stream().map(m -> (Long) m.get("id")).toList());
    }

    /** Check every provider request, including synthesis and verification, after serialization. */
    public Context measure(String system, String user, List<Definition> tools) {
        int count =
                tokens.count(system)
                        + tokens.count(user)
                        + tools.stream().mapToInt(t -> tokens.count(json.write(t))).sum()
                        + 128;
        if (count > config.inputs())
            throw new IllegalArgumentException(
                    "Provider request exceeds the configured input token budget");
        List<Long> ids = new ArrayList<>();
        int compressed = 0;
        try {
            var body = json.tree(user);
            for (var item : body.path("memories")) ids.add(item.path("id").asLong());
            for (var item : body.path("observations"))
                if (item.path("compressed").asBoolean()) compressed++;
        } catch (Exception ignored) {
        }
        return new Context(system, user, tools, count, compressed, ids);
    }

    public void record(Claim run, int invocation, String purpose, Context context) {
        var manifest =
                Map.of(
                        "encoding",
                        "O200K_ESTIMATE",
                        "estimatedInputTokens",
                        context.estimatedTokens(),
                        "inputBudget",
                        config.inputs(),
                        "reservedOutputTokens",
                        config.outputs(),
                        "compressedResults",
                        context.compressedResults(),
                        "memoryIds",
                        context.memoryIds(),
                        "tools",
                        context.tools().stream().map(Definition::name).toList(),
                        "sourceCount",
                        run.state().sources().size());
        jdbc.update(
                "INSERT IGNORE INTO"
                    + " agent_context_manifest(run_id,invocation_no,purpose,manifest_json,create_time)"
                    + " VALUES (?,?,?,?,NOW(6))",
                run.id(),
                invocation,
                purpose,
                json.write(manifest));
    }
}
