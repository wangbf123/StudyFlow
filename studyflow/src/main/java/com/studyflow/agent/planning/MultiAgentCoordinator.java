package com.studyflow.agent.planning;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.*;
import com.studyflow.agent.state.AgentStateStore;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class MultiAgentCoordinator {
    private final AgentStateStore store;
    private final JdbcTemplate jdbc;
    private final AgentJson json;
    private final HarnessProperties config;

    public NodeResult dispatch(Claim parent) {
        var ids = parent.state().scope(parent.knowledgeBaseId());
        int count = Math.min(Math.clamp(config.getMaxTeamMembers(), 1, 3), ids.size());
        var children = new ArrayList<Long>();
        var partitions = new ArrayList<List<Long>>();
        for (int i = 0; i < count; i++) partitions.add(new ArrayList<>());
        for (int i = 0; i < ids.size(); i++) partitions.get(i % count).add(ids.get(i));
        for (int i = 0; i < count; i++)
            children.add(store.createChild(parent, partitions.get(i), i).id());
        return new NodeResult(
                parent.state(),
                "WAIT_CHILDREN",
                "team_dispatched",
                Map.of(
                        "children",
                        children,
                        "scopePolicy",
                        "SUBSET_ONLY",
                        "budgetPolicy",
                        "SHARED_ROOT"),
                null);
    }

    public NodeResult collect(Claim parent) {
        var children = store.children(parent.userId(), parent.id());
        if (children.stream().anyMatch(r -> !r.terminal()))
            return new NodeResult(
                    parent.state(),
                    "WAIT_CHILDREN",
                    "team_waiting",
                    Map.of(
                            "completed",
                            children.stream().filter(Run::terminal).count(),
                            "total",
                            children.size()),
                    null);
        var sources = new LinkedHashMap<String, Source>();
        parent.state()
                .sources()
                .forEach(s -> sources.put(s.chunk().source() + ":" + s.chunk().chunkId(), s));
        var summaries = new ArrayList<Map<String, Object>>();
        for (var child : children) {
            if (!parent.state()
                    .scope(parent.knowledgeBaseId())
                    .containsAll(child.knowledgeBaseIds()))
                throw new IllegalStateException("Child scope changed");
            var rows =
                    jdbc.query(
                            "SELECT evidence_json,markdown FROM agent_artifact WHERE run_id=?",
                            (rs, n) -> new String[] {rs.getString(1), rs.getString(2)},
                            child.id());
            if (!rows.isEmpty()) {
                var evidence = json.tree(rows.getFirst()[0]);
                for (var item : evidence.path("sources")) {
                    var source = json.read(item.toString(), Source.class);
                    if (sources.size() < 20)
                        sources.putIfAbsent(
                                source.chunk().source() + ":" + source.chunk().chunkId(), source);
                }
                summaries.add(
                        Map.of(
                                "runId",
                                child.id(),
                                "status",
                                child.status(),
                                "scope",
                                child.knowledgeBaseIds(),
                                "summary",
                                AgentJson.clip(rows.getFirst()[1], 1200)));
            } else
                summaries.add(
                        Map.of(
                                "runId",
                                child.id(),
                                "status",
                                child.status(),
                                "scope",
                                child.knowledgeBaseIds()));
        }
        var observation =
                new Observation(
                        parent.state().observations().size() + 1,
                        "team_research",
                        "SUCCESS",
                        null,
                        json.write(summaries),
                        "子任务已结束；结果仍需主任务统一核验");
        return new NodeResult(
                parent.state().withTool(observation, List.copyOf(sources.values())),
                "DECIDE",
                "team_collected",
                summaries,
                null);
    }
}
