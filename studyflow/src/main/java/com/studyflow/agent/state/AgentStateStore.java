package com.studyflow.agent.state;

import static com.studyflow.agent.AgentTypes.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.studyflow.agent.*;
import com.studyflow.agent.verification.AgentEvidenceAccess;
import com.studyflow.common.BusinessException;
import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.rag.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Domain checkpoint store. Run state, ordered events and artifact publication share one InnoDB
 * commit.
 */
@Service
@RequiredArgsConstructor
public class AgentStateStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final KnowledgeBaseService knowledgeBases;
    private final AgentJson json;
    private final AgentProperties properties;
    private final AgentEvidenceAccess evidenceAccess;
    private final com.studyflow.agent.memory.MemorySystem memory;
    private final ToolResultStore toolResults;
    private final com.studyflow.agent.planning.TaskManager plans;

    public Run create(Long userId, CreateRequest request) {
        return create(userId, request, null);
    }

    private Run create(Long userId, CreateRequest request, Long retryOf) {
        return createInternal(userId, request, retryOf, null);
    }

    public Run createChild(Claim parent, Long kb, int index) {
        return createChild(parent, List.of(kb), index);
    }

    public Run createChild(Claim parent, List<Long> scopes, int index) {
        if (index < 0 || index >= 3) throw new BusinessException(400, "子任务数量超出上限");
        if (scopes == null || scopes.isEmpty()) throw new BusinessException(400, "子任务范围不能为空");
        Long kb = scopes.getFirst();
        if (parent.depth() != 0
                || !parent.state().scope(parent.knowledgeBaseId()).containsAll(scopes))
            throw new BusinessException(403, "子任务不能扩大范围或继续递归");
        Long context = parent.state().contextNoteId();
        if (context != null
                && jdbc.queryForObject(
                                "SELECT COUNT(*) FROM knowledge_note WHERE id=? AND"
                                        + " knowledge_base_id=? AND user_id=? AND status='ACTIVE'",
                                Integer.class,
                                context,
                                kb,
                                parent.userId())
                        != 1) context = null;
        return createInternal(
                parent.userId(),
                new CreateRequest(
                        kb,
                        null,
                        parent.goal(),
                        "child-" + parent.id() + "-" + index,
                        scopes,
                        parent.state().skill(),
                        context,
                        false),
                null,
                parent);
    }

    private Run createInternal(Long userId, CreateRequest request, Long retryOf, Claim parent) {
        if (!properties.isEnabled()) throw new BusinessException(503, "Agent 暂未启用");
        String goal = request.goal().trim();
        if (goal.isBlank() || goal.length() > 2000)
            throw new BusinessException(400, "目标须为 1 至 2000 字符");
        List<Long> scopes =
                new ArrayList<>(
                        request.knowledgeBaseIds() == null
                                ? List.of()
                                : request.knowledgeBaseIds());
        if (!scopes.contains(request.knowledgeBaseId())) scopes.add(request.knowledgeBaseId());
        scopes = scopes.stream().distinct().sorted().toList();
        if (scopes.size() > 8) throw new BusinessException(400, "一次任务最多选择 8 个知识库");
        final List<Long> selectedScopes = scopes;
        String skill = request.skill() == null ? "research" : request.skill();
        if (!Set.of("research", "compare", "review", "organize", "coding").contains(skill))
            throw new BusinessException(400, "未知任务 Skill");
        String key =
                request.requestKey() == null ? UUID.randomUUID().toString() : request.requestKey();
        String hash =
                EvidenceAnswerValidator.sha256(
                        json.write(
                                Arrays.asList(
                                        request.knowledgeBaseId(),
                                        request.sessionId(),
                                        goal,
                                        retryOf,
                                        selectedScopes,
                                        skill,
                                        request.contextNoteId(),
                                        request.collaboration(),
                                        parent == null ? null : parent.id())));
        Long id =
                transactions.execute(tx -> {
                    // Serialize admission and idempotency per user, across every server instance.
                    if (jdbc.query(
                                    "SELECT id FROM sys_user WHERE id=? AND status='ACTIVE' FOR"
                                            + " UPDATE",
                                    (rs, n) -> rs.getLong(1),
                                    userId)
                            .isEmpty()) throw new BusinessException(401, "账号不可用");
                    if (parent != null) {
                        lockRoot(parent.id());
                        if (live(parent) == null) throw new LeaseLost();
                    }
                    var existing =
                            jdbc.query(
                                    "SELECT id,request_hash FROM agent_run WHERE user_id=? AND"
                                            + " request_key=?",
                                    (rs, n) -> Map.entry(rs.getLong(1), rs.getString(2)),
                                    userId,
                                    key);
                    if (!existing.isEmpty()) {
                        if (!hash.equals(existing.getFirst().getValue()))
                            throw new BusinessException(409, "同一请求标识不能提交不同任务");
                        return existing.getFirst().getKey();
                    }
                    for (Long scope : selectedScopes) knowledgeBases.getOwnedActive(userId, scope);
                    if (request.contextNoteId() != null) {
                        var noteScopes =
                                jdbc.query(
                                        "SELECT knowledge_base_id FROM knowledge_note WHERE id=?"
                                                + " AND user_id=? AND status='ACTIVE'",
                                        (rs, n) -> rs.getLong(1),
                                        request.contextNoteId(),
                                        userId);
                        if (noteScopes.isEmpty() || !selectedScopes.contains(noteScopes.getFirst()))
                            throw new BusinessException(400, "引用笔记不在本次知识库范围中");
                    }
                    if (jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM agent_run WHERE user_id=? AND"
                                                + " runtime_version=2 AND parent_run_id IS NULL AND"
                                                + " status IN ('QUEUED','RUNNING','RETRY_WAIT')",
                                            Integer.class,
                                            userId)
                                    >= 4
                            && parent == null)
                        throw new BusinessException(429, "同时最多运行 4 个 Agent 任务，请等待或取消现有任务");
                    Long session = request.sessionId();
                    if (session == null) {
                        session = IdWorker.getId();
                        jdbc.update(
                                "INSERT INTO"
                                    + " agent_session(id,user_id,knowledge_base_id,title,status,create_time,update_time)"
                                    + " VALUES (?,?,?,?,'ACTIVE',NOW(),NOW())",
                                session,
                                userId,
                                request.knowledgeBaseId(),
                                AgentJson.clip(goal.replaceAll("\\s+", " "), 120));
                    } else {
                        var selected = ownedSession(userId, session);
                        if (!request.knowledgeBaseId().equals(selected.knowledgeBaseId()))
                            throw new BusinessException(400, "会话不属于当前知识库");
                        if (jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM agent_run WHERE session_id=? AND"
                                                + " status IN ('QUEUED','RUNNING','RETRY_WAIT')",
                                        Integer.class,
                                        session)
                                > 0) throw new BusinessException(409, "此会话已有任务正在执行");
                    }
                    var history = new ArrayList<String>();
                    var priorMessages =
                            jdbc.query(
                                    "SELECT m.role,m.content,r.state_json FROM agent_message m JOIN"
                                        + " agent_run r ON r.id=m.run_id WHERE m.session_id=? AND"
                                        + " r.status='SUCCESS' ORDER BY m.id DESC LIMIT 24",
                                    (rs, n) ->
                                            new String[] {
                                                rs.getString(1), rs.getString(2), rs.getString(3)
                                            },
                                    session);
                    for (var item : priorMessages) {
                        if (history.size() >= Math.clamp(properties.getMaxHistoryMessages(), 0, 8))
                            break;
                        if (item[2] == null) continue;
                        var previous = json.read(item[2], CheckpointState.class);
                        if (selectedScopes.containsAll(previous.scope(request.knowledgeBaseId())))
                            history.add(item[0] + ": " + AgentJson.clip(item[1], 800));
                    }
                    Collections.reverse(history);
                    long runId = IdWorker.getId();
                    jdbc.update(
                            """
                            INSERT INTO agent_run(id,session_id,user_id,knowledge_base_id,goal,status,total_steps,runtime_version,stage,last_node,state_json,
                              request_key,request_hash,deadline_at,next_attempt_at,retry_of,prompt_tokens,completion_tokens,create_time,update_time)
                            VALUES (?,?,?,?,?,'QUEUED',0,2,'PLAN','__START__',?,?,?,TIMESTAMPADD(SECOND,?,NOW(6)),NOW(6),?,0,0,NOW(),NOW())
                            """,
                            runId,
                            session,
                            userId,
                            request.knowledgeBaseId(),
                            goal,
                            json.write(
                                    CheckpointState.initial(List.copyOf(history), properties)
                                            .withScope(selectedScopes, skill)
                                            .withContext(request.contextNoteId())
                                            .withTeam(
                                                    Boolean.TRUE.equals(request.collaboration()),
                                                    new Budget(
                                                            parent == null ? properties.steps() : 3,
                                                            parent == null
                                                                    ? (Boolean.TRUE.equals(
                                                                                    request
                                                                                            .collaboration())
                                                                            ? Math.min(
                                                                                    40,
                                                                                    properties
                                                                                                    .calls()
                                                                                            * 3)
                                                                            : properties.calls())
                                                                    : 8,
                                                            Boolean.TRUE.equals(
                                                                            request.collaboration())
                                                                    ? Math.min(
                                                                            200000,
                                                                            properties.tokens() * 3)
                                                                    : properties.tokens(),
                                                            properties.deadlineSeconds()))),
                            key,
                            hash,
                            properties.deadlineSeconds(),
                            retryOf);
                    jdbc.update(
                            "UPDATE agent_run SET"
                                + " parent_run_id=?,root_run_id=?,depth=?,agent_role=? WHERE id=?",
                            parent == null ? null : parent.id(),
                            parent == null ? runId : parent.rootId(),
                            parent == null ? 0 : 1,
                            parent == null ? "coordinator" : "researcher",
                            runId);
                    if (parent != null) {
                        var deadline =
                                jdbc.queryForObject(
                                        "SELECT deadline_at FROM agent_run WHERE id=?",
                                        java.sql.Timestamp.class,
                                        parent.rootId());
                        jdbc.update(
                                "UPDATE agent_run SET deadline_at=LEAST(deadline_at,?) WHERE id=?",
                                deadline,
                                runId);
                    }
                    message(session, runId, "USER", goal);
                    event(
                            runId,
                            "run_created",
                            Map.of(
                                    "runId",
                                    runId,
                                    "sessionId",
                                    session,
                                    "goal",
                                    goal,
                                    "runtimeVersion",
                                    2));
                    return runId;
                });
        return get(userId, id);
    }

    public Run retry(Long userId, Long id, RetryRequest request) {
        Run previous = get(userId, id);
        if (!Set.of("FAILED", "CANCELLED", "TIMED_OUT", "BUDGET_EXCEEDED", "ABSTAINED")
                .contains(previous.status())) throw new BusinessException(409, "只有未完成目标的任务可重试");
        var previousState = owned(userId, id).state();
        // New run, fresh evidence and fresh budget; preserve the requested collaboration mode.
        // The previous trace remains immutable.
        return create(
                userId,
                new CreateRequest(
                        previous.knowledgeBaseId(),
                        previous.sessionId(),
                        previous.goal(),
                        request.requestKey(),
                        previous.knowledgeBaseIds(),
                        previous.skill(),
                        previous.contextNoteId(),
                        previousState != null
                                && Boolean.TRUE.equals(previousState.collaboration())),
                id);
    }

    public List<Long> due() {
        return jdbc.query(
                """
                SELECT id FROM agent_run WHERE runtime_version=2 AND
                  ((status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=NOW(6)) OR
                   (status='RUNNING' AND lease_until<=NOW(6))) ORDER BY next_attempt_at,id LIMIT 16
                """,
                (rs, n) -> rs.getLong(1));
    }

    public Claim claim(Long id) {
        return transactions.execute(tx -> {
            lockRoot(id);
            var rows = jdbc.query("SELECT * FROM agent_run WHERE id=? FOR UPDATE", this::row, id);
            if (rows.isEmpty()) return null;
            var row = rows.getFirst();
            if (row.version() != 2 || TERMINAL.contains(row.status())) return null;
            // Old instances can still insert during a rolling schema upgrade. Initialize
            // their counters lazily under the same root lock instead of assuming NOT NULL.
            jdbc.update(
                    "UPDATE agent_run SET"
                        + " root_run_id=id,root_model_calls=model_calls,root_prompt_tokens=COALESCE(prompt_tokens,0),root_completion_tokens=COALESCE(completion_tokens,0),root_token_charge=COALESCE(prompt_tokens,0)+COALESCE(completion_tokens,0)"
                        + " WHERE id=? AND root_run_id IS NULL",
                    id);
            if (row.depth() > 0
                    && TERMINAL.contains(
                            jdbc.queryForObject(
                                    "SELECT status FROM agent_run WHERE id=?",
                                    String.class,
                                    row.rootId()))) {
                terminal(id, "CANCELLED", "父任务已结束");
                return null;
            }
            boolean due =
                    jdbc.queryForObject(
                            "SELECT (status IN ('QUEUED','RETRY_WAIT') AND next_attempt_at<=NOW(6))"
                                + " OR (status='RUNNING' AND lease_until<=NOW(6)) FROM agent_run"
                                + " WHERE id=?",
                            Boolean.class,
                            id);
            if (!due) return null;
            if (!activeScope(row)) {
                terminal(id, "CANCELLED", "知识库已不可用");
                return null;
            }
            if (expired(id)) {
                terminal(id, "TIMED_OUT", "任务已达到时间预算");
                return null;
            }
            if (row.attempts() >= properties.attempts()) {
                terminal(id, "FAILED", "此节点多次执行失败，请查看执行记录后重试");
                return null;
            }
            String token = UUID.randomUUID().toString();
            jdbc.update(
                    "UPDATE agent_run SET"
                        + " status='RUNNING',claim_token=?,lease_until=TIMESTAMPADD(SECOND,?,NOW(6)),attempts=attempts+1,update_time=NOW()"
                        + " WHERE id=?",
                    token,
                    properties.leaseSeconds(),
                    id);
            if ("RUNNING".equals(row.status())) {
                settleReservation(row.id(), row.rootId(), null);
                jdbc.update("UPDATE agent_run SET usage_incomplete=TRUE WHERE id=?", id);
                event(
                        id,
                        "lease_recovered",
                        Map.of("node", row.stage(), "attempt", row.attempts() + 1));
            }
            event(id, "node_started", Map.of("node", row.stage(), "attempt", row.attempts() + 1));
            return new Claim(
                    row.id(),
                    row.sessionId(),
                    row.userId(),
                    row.kbId(),
                    row.goal(),
                    row.stage(),
                    row.lastNode(),
                    token,
                    row.attempts() + 1,
                    row.state(),
                    row.rootId(),
                    row.depth(),
                    row.role());
        });
    }

    /** Reserve BEFORE every model request, so a crash cannot silently reset the call budget. */
    public void reserveModel(Claim claim) {
        reserveModel(claim, 0);
    }

    public void reserveModel(Claim claim, int reservation) {
        boolean reserved =
                Boolean.TRUE.equals(
                        transactions.execute(tx -> {
                            Row row = live(claim);
                            if (row == null) return false;
                            var root =
                                    jdbc.queryForObject(
                                            "SELECT * FROM agent_run WHERE id=? FOR UPDATE",
                                            this::row,
                                            claim.rootId());
                            var budget = row.state().budget();
                            if (row.modelCalls() >= budget.maxModelCalls()
                                    || root.rootCalls() >= root.state().budget().maxModelCalls()
                                    || jdbc.queryForObject(
                                                    "SELECT"
                                                        + " root_token_charge+root_reserved_tokens+?"
                                                        + " FROM agent_run WHERE id=? FOR UPDATE",
                                                    Long.class,
                                                    reservation,
                                                    claim.rootId())
                                            > root.state().budget().maxReportedTokens()) {
                                terminal(claim.id(), "BUDGET_EXCEEDED", "模型调用或已报告 token 达到预算");
                                return false;
                            }
                            jdbc.update(
                                    "UPDATE agent_run SET"
                                        + " model_calls=model_calls+1,inflight_reserved_tokens=inflight_reserved_tokens+?"
                                        + " WHERE id=?",
                                    reservation,
                                    claim.id());
                            jdbc.update(
                                    "UPDATE agent_run SET"
                                        + " root_model_calls=root_model_calls+1,root_reserved_tokens=root_reserved_tokens+?"
                                        + " WHERE id=?",
                                    reservation,
                                    claim.rootId());
                            return true;
                        }));
        if (!reserved) throw new LeaseLost();
    }

    public void usage(Claim claim, String model, Integer prompt, Integer completion) {
        boolean saved =
                Boolean.TRUE.equals(
                        transactions.execute(tx -> {
                            if (live(claim) == null) return false;
                            jdbc.update(
                                    "UPDATE agent_run SET"
                                        + " model_name=?,prompt_tokens=prompt_tokens+?,completion_tokens=completion_tokens+?,usage_incomplete=usage_incomplete"
                                        + " OR ? WHERE id=?",
                                    AgentJson.clip(model, 120),
                                    boundedTokens(prompt),
                                    boundedTokens(completion),
                                    prompt == null || completion == null,
                                    claim.id());
                            settleReservation(
                                    claim.id(),
                                    claim.rootId(),
                                    prompt == null || completion == null
                                            ? null
                                            : (long) boundedTokens(prompt)
                                                    + boundedTokens(completion));
                            jdbc.update(
                                    "UPDATE agent_run SET"
                                        + " root_prompt_tokens=root_prompt_tokens+?,root_completion_tokens=root_completion_tokens+?"
                                        + " WHERE id=?",
                                    boundedTokens(prompt),
                                    boundedTokens(completion),
                                    claim.rootId());
                            return true;
                        }));
        if (!saved) throw new LeaseLost();
    }

    public boolean checkpoint(Claim claim, NodeResult result, long latencyMs) {
        return Boolean.TRUE.equals(
                transactions.execute(tx -> {
                    Row row = live(claim);
                    if (row == null) return false;
                    if (jdbc.queryForObject(
                                    "SELECT root_token_charge FROM agent_run WHERE id=? FOR UPDATE",
                                    Long.class,
                                    claim.rootId())
                            > jdbc.queryForObject(
                                    "SELECT state_json FROM agent_run WHERE id=?",
                                    (rs, n) ->
                                            json.read(rs.getString(1), CheckpointState.class)
                                                    .budget()
                                                    .maxReportedTokens(),
                                    claim.rootId())) {
                        terminal(claim.id(), "BUDGET_EXCEEDED", "模型已报告 token 超出预算，停止后续执行");
                        return false;
                    }
                    java.util.Map<String, java.util.Set<String>> transitions =
                            java.util.Map.of(
                                    "PLAN",
                                    Set.of("DECIDE", "TEAM_DISPATCH"),
                                    "TEAM_DISPATCH",
                                    Set.of("WAIT_CHILDREN"),
                                    "WAIT_CHILDREN",
                                    Set.of("WAIT_CHILDREN", "DECIDE"),
                                    "DECIDE",
                                    Set.of("TOOL", "DECIDE", "SYNTHESIZE"),
                                    "TOOL",
                                    Set.of("DECIDE"),
                                    "SYNTHESIZE",
                                    Set.of("VERIFY"),
                                    "VERIFY",
                                    Set.of("__END__"));
                    if (!transitions
                            .getOrDefault(claim.stage(), Set.of())
                            .contains(result.nextNode()))
                        throw new IllegalArgumentException("Invalid agent transition");
                    var eventData = new LinkedHashMap<String, Object>();
                    eventData.put("node", claim.stage());
                    eventData.put("latencyMs", latencyMs);
                    eventData.put("result", result.eventData());
                    if (!claim.stage().equals("WAIT_CHILDREN")
                            || !result.nextNode().equals("WAIT_CHILDREN"))
                        event(claim.id(), result.eventType(), eventData);
                    if (claim.stage().equals("PLAN")) plans.save(claim.id(), result.state().plan());
                    if (result.state().observations().size()
                            > claim.state().observations().size()) {
                        var observation = result.state().observations().getLast();
                        plans.commitCompletion(claim, observation);
                        toolResults.save(
                                claim,
                                observation.number(),
                                observation.tool(),
                                observation.content());
                    }
                    jdbc.update(
                            "UPDATE agent_run SET"
                                + " state_json=?,last_node=?,stage=?,total_steps=?,attempts=0,claim_token=NULL,lease_until=NULL,status='QUEUED',next_attempt_at=NOW(6),error_message=NULL,update_time=NOW()"
                                + " WHERE id=?",
                            json.write(result.state()),
                            claim.stage(),
                            result.nextNode(),
                            result.state().observations().size(),
                            claim.id());
                    if (result.nextNode().equals("WAIT_CHILDREN"))
                        jdbc.update(
                                "UPDATE agent_run SET next_attempt_at=TIMESTAMPADD(SECOND,2,NOW(6))"
                                        + " WHERE id=?",
                                claim.id());
                    if (result.verified() != null) publish(claim, result);
                    return true;
                }));
    }

    public void fail(Claim claim, boolean timeout) {
        transactions.executeWithoutResult(tx -> {
            Row row = live(claim);
            if (row == null) return;
            settleReservation(claim.id(), claim.rootId(), null);
            // Unknown provider exceptions never enter user-visible traces, prompts or public logs.
            String message = timeout ? "节点执行超时，将从上次检查点恢复" : "节点执行失败，将从上次检查点恢复";
            event(
                    claim.id(),
                    "node_failed",
                    Map.of("node", claim.stage(), "attempt", claim.attempts(), "message", message));
            if (row.attempts() >= properties.attempts())
                terminal(claim.id(), "FAILED", "此节点已达到重试上限");
            else
                jdbc.update(
                        "UPDATE agent_run SET"
                            + " status='RETRY_WAIT',claim_token=NULL,lease_until=NULL,next_attempt_at=TIMESTAMPADD(SECOND,?,NOW(6)),error_message=?,usage_incomplete=TRUE,update_time=NOW()"
                            + " WHERE id=?",
                        Math.min(30, 2 * row.attempts()),
                        message,
                        claim.id());
        });
    }

    public Run cancel(Long userId, Long id) {
        get(userId, id);
        transactions.executeWithoutResult(tx -> {
            lockRoot(id);
            Row row =
                    jdbc.queryForObject(
                            "SELECT * FROM agent_run WHERE id=? AND user_id=? FOR UPDATE",
                            this::row,
                            id,
                            userId);
            if (row != null && !TERMINAL.contains(row.status()))
                terminal(id, "CANCELLED", "用户取消了此任务");
        });
        return get(userId, id);
    }

    public List<Session> sessions(Long userId, Long kbId) {
        knowledgeBases.getOwnedActive(userId, kbId);
        return jdbc.query(
                "SELECT * FROM agent_session WHERE user_id=? AND knowledge_base_id=? AND"
                        + " status='ACTIVE' ORDER BY update_time DESC,id DESC LIMIT 40",
                this::session,
                userId,
                kbId);
    }

    public List<Run> runs(Long userId, Long sessionId) {
        var session = ownedSession(userId, sessionId);
        knowledgeBases.getOwnedActive(userId, session.knowledgeBaseId());
        return jdbc
                .query(
                        "SELECT * FROM agent_run WHERE user_id=? AND session_id=? ORDER BY id DESC"
                                + " LIMIT 30",
                        this::row,
                        userId,
                        sessionId)
                .stream()
                .map(row -> response(row, null))
                .toList();
    }

    public Run get(Long userId, Long id) {
        Row row = owned(userId, id);
        return response(row, artifact(row));
    }

    public List<Event> events(Long userId, Long id, int after) {
        owned(userId, id);
        if (after < 0) throw new BusinessException(400, "无效事件游标");
        return jdbc.query(
                "SELECT * FROM agent_event WHERE run_id=? AND seq>? ORDER BY seq LIMIT 100",
                (rs, n) ->
                        new Event(
                                id,
                                rs.getInt("seq"),
                                rs.getString("type"),
                                json.tree(rs.getString("payload_json")),
                                time(rs, "create_time")),
                id,
                after);
    }

    private Row owned(Long userId, Long id) {
        var rows =
                jdbc.query(
                        "SELECT * FROM agent_run WHERE id=? AND user_id=?", this::row, id, userId);
        if (rows.isEmpty()) throw new BusinessException(404, "任务不存在或无权访问");
        var row = rows.getFirst();
        knowledgeBases.getOwnedActive(userId, row.kbId());
        return row;
    }

    private Session ownedSession(Long userId, Long id) {
        var rows =
                jdbc.query(
                        "SELECT * FROM agent_session WHERE id=? AND user_id=? AND status='ACTIVE'",
                        this::session,
                        id,
                        userId);
        if (rows.isEmpty()) throw new BusinessException(404, "会话不存在或无权访问");
        return rows.getFirst();
    }

    private boolean activeScope(Row row) {
        for (Long id : row.state().scope(row.kbId())) if (!activeKb(row.userId(), id)) return false;
        return true;
    }

    private boolean activeKb(Long userId, Long kbId) {
        return !jdbc.query(
                        "SELECT id FROM knowledge_base WHERE id=? AND user_id=? AND status='ACTIVE'"
                                + " FOR SHARE",
                        (rs, n) -> rs.getLong(1),
                        kbId,
                        userId)
                .isEmpty();
    }

    private boolean expired(Long id) {
        return Boolean.TRUE.equals(
                jdbc.queryForObject(
                        "SELECT deadline_at<=NOW(6) FROM agent_run WHERE id=?", Boolean.class, id));
    }

    public int modelCalls(Long id) {
        return jdbc.queryForObject(
                "SELECT model_calls FROM agent_run WHERE id=?", Integer.class, id);
    }

    public void uncertainUsage(Claim claim) {
        transactions.executeWithoutResult(tx -> {
            if (live(claim) != null) {
                settleReservation(claim.id(), claim.rootId(), null);
                jdbc.update("UPDATE agent_run SET usage_incomplete=TRUE WHERE id=?", claim.id());
            }
        });
    }

    private void settleReservation(Long id, Long root, Long actual) {
        long held =
                jdbc.queryForObject(
                        "SELECT inflight_reserved_tokens FROM agent_run WHERE id=? FOR UPDATE",
                        Long.class,
                        id);
        jdbc.update(
                "UPDATE agent_run SET"
                    + " root_reserved_tokens=GREATEST(0,root_reserved_tokens-?),root_token_charge=root_token_charge+?"
                    + " WHERE id=?",
                held,
                actual == null ? held : actual,
                root);
        jdbc.update(
                "UPDATE agent_run SET inflight_reserved_tokens=0,usage_incomplete=usage_incomplete"
                        + " OR ? WHERE id=?",
                actual == null && held > 0,
                id);
    }

    public List<Run> children(Long user, Long id) {
        owned(user, id);
        return jdbc
                .query(
                        "SELECT * FROM agent_run WHERE parent_run_id=? AND user_id=? ORDER BY id",
                        this::row,
                        id,
                        user)
                .stream()
                .map(row -> response(row, null))
                .toList();
    }

    private void lockRoot(Long id) {
        var ids =
                jdbc.query(
                        "SELECT COALESCE(root_run_id,id) FROM agent_run WHERE id=?",
                        (rs, n) -> rs.getLong(1),
                        id);
        if (!ids.isEmpty())
            jdbc.queryForObject(
                    "SELECT id FROM agent_run WHERE id=? FOR UPDATE", Long.class, ids.getFirst());
    }

    private Row live(Claim claim) {
        lockRoot(claim.id());
        if (claim.depth() > 0) {
            var rootStatus =
                    jdbc.queryForObject(
                            "SELECT status FROM agent_run WHERE id=?",
                            String.class,
                            claim.rootId());
            if (TERMINAL.contains(rootStatus)) return null;
        }
        var rows =
                jdbc.query(
                        "SELECT * FROM agent_run WHERE id=? AND status='RUNNING' AND claim_token=?"
                                + " AND lease_until>NOW(6) FOR UPDATE",
                        this::row,
                        claim.id(),
                        claim.token());
        if (rows.isEmpty()) return null;
        if (expired(claim.id())) {
            terminal(claim.id(), "TIMED_OUT", "任务已达到时间预算");
            return null;
        }
        if (!activeScope(rows.getFirst())) {
            terminal(claim.id(), "CANCELLED", "知识库已不可用");
            return null;
        }
        return rows.getFirst();
    }

    private void terminal(Long id, String status, String error) {
        Long root =
                jdbc.queryForObject(
                        "SELECT COALESCE(root_run_id,id) FROM agent_run WHERE id=?",
                        Long.class,
                        id);
        settleReservation(id, root, null);
        jdbc.update(
                "UPDATE agent_run SET"
                    + " status=?,stage='DONE',claim_token=NULL,lease_until=NULL,error_message=?,latency_ms=TIMESTAMPDIFF(MICROSECOND,create_time,NOW(6))/1000,update_time=NOW()"
                    + " WHERE id=?",
                status,
                error,
                id);
        for (Long child :
                jdbc.query(
                        "SELECT id FROM agent_run WHERE parent_run_id=? AND status IN"
                                + " ('QUEUED','RUNNING','RETRY_WAIT') ORDER BY id FOR UPDATE",
                        (rs, n) -> rs.getLong(1),
                        id)) terminal(child, "CANCELLED", "父任务已结束");
        event(id, "run_finished", Map.of("status", status, "message", error == null ? "" : error));
        jdbc.update(
                "UPDATE agent_session SET update_time=NOW() WHERE id=(SELECT session_id FROM"
                        + " agent_run WHERE id=?)",
                id);
    }

    private void event(Long id, String type, Object data) {
        jdbc.update("UPDATE agent_run SET event_seq=event_seq+1 WHERE id=?", id);
        jdbc.update(
                "INSERT INTO agent_event(run_id,seq,type,payload_json,create_time) SELECT"
                        + " id,event_seq,?,?,NOW(6) FROM agent_run WHERE id=?",
                type,
                json.write(data),
                id);
    }

    private void message(Long session, Long run, String role, String text) {
        jdbc.update(
                "INSERT INTO agent_message(id,session_id,run_id,role,content,create_time) VALUES"
                        + " (?,?,?,?,?,NOW())",
                IdWorker.getId(),
                session,
                run,
                role,
                text);
    }

    private int boundedTokens(Integer value) {
        return value == null ? 0 : Math.clamp(value, 0, 10_000_000);
    }

    private void publish(Claim claim, NodeResult result) {
        VerifiedAnswer verified = result.verified();
        var citedIds =
                new HashSet<>(
                        verified.citedChunks().stream().map(RetrievedChunk::chunkId).toList());
        List<Source> cited =
                result.state().sources().stream()
                        .filter(s -> citedIds.contains(s.chunk().chunkId()))
                        .sorted(Comparator.comparing(s -> s.chunk().documentId()))
                        .toList();
        // Recheck under document shared locks in the publication transaction. Rebuild/delete cannot
        // race the commit.
        boolean current =
                citedIds.size() == cited.size()
                        && sourcesCurrent(claim.userId(), claim.knowledgeBaseId(), cited, true);
        if (!current)
            verified =
                    new VerifiedAnswer(
                            "资料已发生变化，请重新执行任务以获取最新证据。",
                            "ABSTAINED",
                            verified.mode(),
                            List.of(),
                            verified.rejectedClaims() + verified.claims().size(),
                            true,
                            null,
                            0,
                            0,
                            0);
        String status = verified.claims().isEmpty() ? "ABSTAINED" : "SUCCESS";
        String title =
                AgentJson.clip(
                        claim.goal().replaceAll("note:[0-9]+", "当前笔记").replaceAll("\\s+", " "),
                        120);
        String markdown = markdown(title, verified, result.state());
        var evidence =
                Map.of(
                        "claims",
                        verified.claims(),
                        "sources",
                        current ? cited : List.of(),
                        "mode",
                        verified.mode(),
                        "rejectedClaims",
                        verified.rejectedClaims());
        jdbc.update(
                "INSERT INTO"
                    + " agent_artifact(run_id,title,markdown,verification_status,evidence_json,create_time)"
                    + " VALUES (?,?,?,?,?,NOW(6))",
                claim.id(),
                title,
                markdown,
                verified.status(),
                json.write(evidence));
        jdbc.update(
                "UPDATE agent_run SET final_answer=? WHERE id=?", verified.answer(), claim.id());
        message(claim.sessionId(), claim.id(), "ASSISTANT", verified.answer());
        event(
                claim.id(),
                "artifact_created",
                Map.of(
                        "verificationStatus",
                        verified.status(),
                        "claimCount",
                        verified.claims().size()));
        memory.rememberRun(claim, status);
        terminal(claim.id(), status, null);
    }

    private String markdown(String title, VerifiedAnswer answer, CheckpointState state) {
        StringBuilder out =
                new StringBuilder("# ")
                        .append(title.replace("#", "\\#"))
                        .append("\n\n")
                        .append("> 证据状态：")
                        .append(answer.status())
                        .append(" · 模式：")
                        .append(answer.mode())
                        .append("\n\n## 结论\n\n")
                        .append(answer.answer());
        out.append("\n\n## 引用原文\n\n");
        Set<String> seen = new HashSet<>();
        answer.claims()
                .forEach(c ->
                        c.evidence()
                                .forEach(e -> {
                                    if (seen.add(e.sourceId() + e.quote()))
                                        out.append("- [")
                                                .append(e.sourceId())
                                                .append("] ")
                                                .append(
                                                        e.chunk()
                                                                .fileName()
                                                                .replaceAll("[\\r\\n]", " "))
                                                .append(
                                                        "NOTE".equals(e.chunk().source())
                                                                ? " [[note:"
                                                                        + e.chunk().chunkId()
                                                                        + "|"
                                                                        + e.chunk()
                                                                                .fileName()
                                                                                .replace("|", " ")
                                                                                .replace("]", " ")
                                                                        + "]]"
                                                                : " · chunk "
                                                                        + e.chunk().chunkIndex())
                                                .append("\n\n  > ")
                                                .append(e.quote().replace("\n", "\n  > "))
                                                .append("\n\n");
                                }));
        out.append("\n## 执行计划\n\n");
        for (int i = 0; i < state.plan().size(); i++)
            out.append(i + 1).append(". ").append(state.plan().get(i)).append('\n');
        out.append("\n---\n由 StudyFlow Agent 生成。原文匹配与模型核验不能替代人工判断。\n");
        return out.toString();
    }

    private boolean sourcesCurrent(Long userId, Long kbId, List<Source> sources, boolean lock) {
        for (var source : sources)
            if (!evidenceAccess.current(userId, source.chunk().knowledgeBaseId(), source, lock))
                return false;
        return true;
    }

    private Artifact artifact(Row row) {
        var values =
                jdbc.query(
                        "SELECT * FROM agent_artifact WHERE run_id=?",
                        (rs, n) -> {
                            JsonNode evidence = json.tree(rs.getString("evidence_json"));
                            List<Source> sources = new ArrayList<>();
                            for (var item : evidence.path("sources"))
                                sources.add(json.read(item.toString(), Source.class));
                            return new Artifact(
                                    rs.getString("title"),
                                    rs.getString("markdown"),
                                    rs.getString("verification_status"),
                                    evidence,
                                    sourcesCurrent(row.userId(), row.kbId(), sources, false),
                                    time(rs, "create_time"));
                        },
                        row.id());
        if (!values.isEmpty()) return values.getFirst();
        if (row.version() == 1 && row.finalAnswer() != null)
            return new Artifact(
                    "旧版回答",
                    row.finalAnswer(),
                    "LEGACY_UNVERIFIED",
                    json.tree("{}"),
                    false,
                    row.updated());
        return null;
    }

    private Run response(Row row, Artifact artifact) {
        var state = row.state();
        boolean incomplete =
                row.usageIncomplete()
                        || (row.depth() == 0
                                && jdbc.queryForObject(
                                                "SELECT COUNT(*) FROM agent_run WHERE root_run_id=?"
                                                        + " AND usage_incomplete=TRUE",
                                                Integer.class,
                                                row.id())
                                        > 0);
        return new Run(
                row.id(),
                row.sessionId(),
                row.kbId(),
                row.goal(),
                row.status(),
                row.stage(),
                row.version(),
                row.modelName(),
                row.steps(),
                row.modelCalls(),
                row.promptTokens(),
                row.completionTokens(),
                incomplete,
                row.error(),
                row.seq(),
                row.retryOf(),
                state == null ? List.of() : state.plan(),
                state == null ? null : state.budget(),
                state == null ? List.of(row.kbId()) : state.scope(row.kbId()),
                state == null ? "research" : state.skill(),
                state == null ? null : state.contextNoteId(),
                artifact,
                row.created(),
                row.updated(),
                row.deadline(),
                row.parentId(),
                row.rootId(),
                row.depth(),
                row.role(),
                row.depth() == 0
                        ? row.rootCalls()
                        : jdbc.queryForObject(
                                "SELECT root_model_calls FROM agent_run WHERE id=?",
                                Integer.class,
                                row.rootId()));
    }

    private Session session(ResultSet rs, int n) throws SQLException {
        return new Session(
                rs.getLong("id"),
                rs.getLong("knowledge_base_id"),
                rs.getString("title"),
                rs.getString("status"),
                time(rs, "update_time"));
    }

    private Row row(ResultSet rs, int n) throws SQLException {
        String state = rs.getString("state_json");
        return new Row(
                rs.getLong("id"),
                rs.getLong("session_id"),
                rs.getLong("user_id"),
                rs.getLong("knowledge_base_id"),
                rs.getString("goal"),
                rs.getString("status"),
                rs.getString("stage"),
                rs.getString("last_node"),
                rs.getInt("runtime_version"),
                state == null ? null : json.read(state, CheckpointState.class),
                rs.getInt("attempts"),
                rs.getInt("event_seq"),
                rs.getInt("total_steps"),
                rs.getInt("model_calls"),
                rs.getInt("prompt_tokens"),
                rs.getInt("completion_tokens"),
                rs.getBoolean("usage_incomplete"),
                rs.getString("model_name"),
                rs.getString("error_message"),
                rs.getObject("retry_of", Long.class),
                rs.getString("final_answer"),
                time(rs, "create_time"),
                time(rs, "update_time"),
                time(rs, "deadline_at"),
                rs.getObject("parent_run_id", Long.class),
                Objects.requireNonNullElse(
                        rs.getObject("root_run_id", Long.class), rs.getLong("id")),
                rs.getInt("depth"),
                rs.getString("agent_role"),
                rs.getInt("root_model_calls"),
                rs.getLong("root_prompt_tokens"),
                rs.getLong("root_completion_tokens"));
    }

    private static LocalDateTime time(ResultSet rs, String name) throws SQLException {
        var stamp = rs.getTimestamp(name);
        return stamp == null ? null : stamp.toLocalDateTime();
    }

    private record Row(
            Long id,
            Long sessionId,
            Long userId,
            Long kbId,
            String goal,
            String status,
            String stage,
            String lastNode,
            int version,
            CheckpointState state,
            int attempts,
            int seq,
            int steps,
            int modelCalls,
            int promptTokens,
            int completionTokens,
            boolean usageIncomplete,
            String modelName,
            String error,
            Long retryOf,
            String finalAnswer,
            LocalDateTime created,
            LocalDateTime updated,
            LocalDateTime deadline,
            Long parentId,
            Long rootId,
            int depth,
            String role,
            int rootCalls,
            long rootInput,
            long rootOutput) {}

    public static class LeaseLost extends RuntimeException {
        public LeaseLost() {
            super("Agent claim is no longer active");
        }
    }
}
