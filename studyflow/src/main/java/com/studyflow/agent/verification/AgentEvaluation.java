package com.studyflow.agent.verification;

import com.studyflow.agent.AgentJson;
import com.studyflow.agent.AgentTypes.CheckpointState;
import com.studyflow.agent.state.AgentStateStore;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** Deterministic operational checks. These scores do not claim to measure factual completeness. */
@Component
@RequiredArgsConstructor
public class AgentEvaluation {
    private final AgentStateStore store;
    private final JdbcTemplate jdbc;
    private final AgentJson json;

    public Map<String, Object> evaluate(Long user, Long id) {
        var run = store.get(user, id);
        var children = store.children(user, id);
        int rootLimit =
                jdbc.queryForObject(
                        "SELECT state_json FROM agent_run WHERE id=?",
                        (rs, n) ->
                                rs.getString(1) == null
                                        ? 0
                                        : json.read(rs.getString(1), CheckpointState.class)
                                                .budget()
                                                .maxReportedTokens(),
                        run.rootRunId());
        long charged =
                jdbc.queryForObject(
                        "SELECT root_token_charge+root_reserved_tokens FROM agent_run WHERE id=?",
                        Long.class,
                        run.rootRunId());
        var checks =
                List.of(
                        Map.of("name", "TASK_TERMINAL", "passed", run.terminal()),
                        Map.of(
                                "name",
                                "CHILD_SCOPE_SUBSET",
                                "passed",
                                children.stream()
                                        .allMatch(child ->
                                                run.knowledgeBaseIds()
                                                                .containsAll(
                                                                        child.knowledgeBaseIds())
                                                        && child.depth() <= 1)),
                        Map.of("name", "SHARED_TOKEN_BUDGET", "passed", charged <= rootLimit),
                        Map.of(
                                "name",
                                "PUBLISHED_EVIDENCE_CURRENT",
                                "passed",
                                run.artifact() != null && run.artifact().evidenceCurrent()));
        return Map.of(
                "checks",
                checks,
                "verificationStatus",
                run.artifact() == null ? "NO_ARTIFACT" : run.artifact().verificationStatus(),
                "claimCount",
                run.artifact() == null ? 0 : run.artifact().evidence().path("claims").size(),
                "semanticCompleteness",
                "NOT_INDEPENDENTLY_SCORED",
                "usageComplete",
                !run.usageIncomplete());
    }
}
