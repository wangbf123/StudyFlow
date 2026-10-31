package com.studyflow.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.studyflow.rag.RetrievedChunk;
import com.studyflow.rag.VerifiedAnswer;

import jakarta.validation.constraints.*;

import java.time.LocalDateTime;
import java.util.*;

/** Versioned API and checkpoint values. Never store model chain-of-thought. */
public final class AgentTypes {
    private AgentTypes() {}

    public static final Set<String> TERMINAL =
            Set.of("SUCCESS", "ABSTAINED", "FAILED", "CANCELLED", "TIMED_OUT", "BUDGET_EXCEEDED");

    public record CreateRequest(
            @NotNull Long knowledgeBaseId,
            Long sessionId,
            @NotBlank @Size(max = 2000) String goal,
            @Pattern(regexp = "[a-zA-Z0-9_-]{8,64}") String requestKey,
            @Size(max = 8) List<@NotNull Long> knowledgeBaseIds,
            @Pattern(regexp = "research|compare|review|organize|coding") String skill,
            Long contextNoteId,
            Boolean collaboration) {
        public CreateRequest(Long knowledgeBaseId, Long sessionId, String goal, String requestKey) {
            this(knowledgeBaseId, sessionId, goal, requestKey, null, null, null, false);
        }

        public CreateRequest(
                Long knowledgeBaseId,
                Long sessionId,
                String goal,
                String requestKey,
                List<Long> ids,
                String skill) {
            this(knowledgeBaseId, sessionId, goal, requestKey, ids, skill, null, false);
        }

        public CreateRequest(
                Long kb,
                Long session,
                String goal,
                String key,
                List<Long> ids,
                String skill,
                Long context) {
            this(kb, session, goal, key, ids, skill, context, false);
        }
    }

    public record RetryRequest(
            @NotBlank @Pattern(regexp = "[a-zA-Z0-9_-]{8,64}") String requestKey) {}

    public record Session(
            Long id, Long knowledgeBaseId, String title, String status, LocalDateTime updateTime) {}

    public record Observation(
            int number,
            String tool,
            String status,
            JsonNode arguments,
            String content,
            String summary) {}

    public record PendingCall(String tool, JsonNode arguments) {}

    public record Source(RetrievedChunk chunk, Long generation, String contentHash) {}

    public record Budget(
            int maxSteps, int maxModelCalls, int maxReportedTokens, int timeoutSeconds) {}

    public record CheckpointState(
            int version,
            List<String> plan,
            List<Observation> observations,
            List<Source> sources,
            PendingCall pendingCall,
            String draft,
            List<RetrievedChunk> draftSources,
            int decisions,
            List<String> history,
            Budget budget,
            List<Long> knowledgeBaseIds,
            String skill,
            Long contextNoteId,
            Boolean collaboration) {
        public static CheckpointState initial(List<String> history, AgentProperties p) {
            return new CheckpointState(
                    2,
                    List.of(),
                    List.of(),
                    List.of(),
                    null,
                    null,
                    List.of(),
                    0,
                    history,
                    new Budget(p.steps(), p.calls(), p.tokens(), p.deadlineSeconds()),
                    List.of(),
                    "research",
                    null,
                    false);
        }

        public CheckpointState withTeam(boolean enabled, Budget value) {
            return new CheckpointState(
                    version,
                    plan,
                    observations,
                    sources,
                    pendingCall,
                    draft,
                    draftSources,
                    decisions,
                    history,
                    value,
                    knowledgeBaseIds,
                    skill,
                    contextNoteId,
                    enabled);
        }

        public CheckpointState withScope(List<Long> ids, String key) {
            return new CheckpointState(
                    version,
                    plan,
                    observations,
                    sources,
                    pendingCall,
                    draft,
                    draftSources,
                    decisions,
                    history,
                    budget,
                    ids,
                    key,
                    contextNoteId,
                    collaboration);
        }

        public CheckpointState withContext(Long id) {
            return new CheckpointState(
                    version,
                    plan,
                    observations,
                    sources,
                    pendingCall,
                    draft,
                    draftSources,
                    decisions,
                    history,
                    budget,
                    knowledgeBaseIds,
                    skill,
                    id,
                    collaboration);
        }

        public List<Long> scope(Long primary) {
            return knowledgeBaseIds == null || knowledgeBaseIds.isEmpty()
                    ? List.of(primary)
                    : knowledgeBaseIds;
        }

        public CheckpointState withPlan(List<String> value) {
            return new CheckpointState(
                    version,
                    value,
                    observations,
                    sources,
                    pendingCall,
                    draft,
                    draftSources,
                    decisions,
                    history,
                    budget,
                    knowledgeBaseIds,
                    skill,
                    contextNoteId,
                    collaboration);
        }

        public CheckpointState withDecision(PendingCall value) {
            return new CheckpointState(
                    version,
                    plan,
                    observations,
                    sources,
                    value,
                    draft,
                    draftSources,
                    decisions + 1,
                    history,
                    budget,
                    knowledgeBaseIds,
                    skill,
                    contextNoteId,
                    collaboration);
        }

        public CheckpointState withTool(Observation value, List<Source> evidence) {
            var items = new ArrayList<>(observations);
            items.add(value);
            return new CheckpointState(
                    version,
                    plan,
                    List.copyOf(items),
                    evidence,
                    null,
                    draft,
                    draftSources,
                    decisions,
                    history,
                    budget,
                    knowledgeBaseIds,
                    skill,
                    contextNoteId,
                    collaboration);
        }

        public CheckpointState withDraft(String value, List<RetrievedChunk> evidence) {
            return new CheckpointState(
                    version,
                    plan,
                    observations,
                    sources,
                    null,
                    value,
                    evidence,
                    decisions,
                    history,
                    budget,
                    knowledgeBaseIds,
                    skill,
                    contextNoteId,
                    collaboration);
        }
    }

    public record Claim(
            Long id,
            Long sessionId,
            Long userId,
            Long knowledgeBaseId,
            String goal,
            String stage,
            String lastNode,
            String token,
            int attempts,
            CheckpointState state,
            Long rootId,
            int depth,
            String role) {
        public Claim(
                Long id,
                Long session,
                Long user,
                Long kb,
                String goal,
                String stage,
                String last,
                String token,
                int attempts,
                CheckpointState state) {
            this(
                    id,
                    session,
                    user,
                    kb,
                    goal,
                    stage,
                    last,
                    token,
                    attempts,
                    state,
                    id,
                    0,
                    "coordinator");
        }
    }

    public record NodeResult(
            CheckpointState state,
            String nextNode,
            String eventType,
            Object eventData,
            VerifiedAnswer verified) {}

    public record Event(
            Long runId, int seq, String type, JsonNode data, LocalDateTime createTime) {}

    public record Artifact(
            String title,
            String markdown,
            String verificationStatus,
            JsonNode evidence,
            boolean evidenceCurrent,
            LocalDateTime createTime) {}

    public record Run(
            Long id,
            Long sessionId,
            Long knowledgeBaseId,
            String goal,
            String status,
            String stage,
            int runtimeVersion,
            String modelName,
            int totalSteps,
            int modelCalls,
            Integer promptTokens,
            Integer completionTokens,
            boolean usageIncomplete,
            String errorMessage,
            int eventSeq,
            Long retryOf,
            List<String> plan,
            Budget budget,
            List<Long> knowledgeBaseIds,
            String skill,
            Long contextNoteId,
            Artifact artifact,
            LocalDateTime createTime,
            LocalDateTime updateTime,
            LocalDateTime deadlineAt,
            Long parentRunId,
            Long rootRunId,
            int depth,
            String role,
            int sharedModelCalls) {
        public boolean terminal() {
            return TERMINAL.contains(status);
        }
    }
}
