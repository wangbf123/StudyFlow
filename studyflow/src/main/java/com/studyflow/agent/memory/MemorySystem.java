package com.studyflow.agent.memory;

import static com.studyflow.agent.AgentTypes.*;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.studyflow.agent.*;
import com.studyflow.agent.governance.AgentPolicy;
import com.studyflow.common.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
public class MemorySystem {
    private final JdbcTemplate jdbc;
    private final AgentJson json;
    private final AgentPolicy policy;

    public record Memory(
            Long id,
            Long knowledgeBaseId,
            String kind,
            String title,
            String content,
            List<Long> scope,
            Long sourceNoteId,
            Integer sourceRevision,
            Long originRunId,
            int revision,
            boolean current) {}

    public record Write(
            Long knowledgeBaseId,
            String kind,
            String title,
            String content,
            Long sourceNoteId,
            Integer ttlDays) {}

    public List<Memory> retrieve(Long user, List<Long> scope, String query, int limit) {
        if (scope.isEmpty()) return List.of();
        var args = new ArrayList<Object>();
        args.add(user);
        args.addAll(scope);
        var rows =
                jdbc.query(
                        "SELECT * FROM (SELECT m.*,ROW_NUMBER() OVER (PARTITION BY kind ORDER BY"
                            + " update_time DESC,id DESC) AS slot_rank FROM agent_memory m WHERE"
                            + " user_id=? AND knowledge_base_id IN ("
                                + String.join(",", Collections.nCopies(scope.size(), "?"))
                                + ") AND status='ACTIVE' AND (expires_at IS NULL OR"
                                + " expires_at>NOW(6))) candidates WHERE slot_rank<=25 ORDER BY"
                                + " update_time DESC,id DESC",
                        (rs, n) -> {
                            List<Long> ids = new ArrayList<>();
                            for (var id : json.tree(rs.getString("scope_json")))
                                ids.add(id.asLong());
                            return new Memory(
                                    rs.getLong("id"),
                                    rs.getLong("knowledge_base_id"),
                                    rs.getString("kind"),
                                    rs.getString("title"),
                                    rs.getString("content"),
                                    ids,
                                    rs.getObject("source_note_id", Long.class),
                                    rs.getObject("source_revision", Integer.class),
                                    rs.getObject("origin_run_id", Long.class),
                                    rs.getInt("revision"),
                                    true);
                        },
                        args.toArray());
        String term =
                query == null ? "" : AgentJson.clip(query, 200).strip().toLowerCase(Locale.ROOT);
        var terms = new HashSet<String>();
        for (String word : term.split("[^\\p{L}\\p{N}]+")) {
            if (word.length() > 1) terms.add(word);
            for (int i = 0; i < word.length() - 1; i++) terms.add(word.substring(i, i + 2));
        }
        var ranked =
                rows.stream()
                        .filter(m -> scope.containsAll(m.scope()))
                        .filter(m -> fresh(user, m))
                        .sorted(
                                Comparator.<Memory>comparingInt(m ->
                                                -(int)
                                                        terms.stream()
                                                                .filter(t ->
                                                                        (m.title()
                                                                                        + " "
                                                                                        + m
                                                                                                .content())
                                                                                .toLowerCase(
                                                                                        Locale.ROOT)
                                                                                .contains(t))
                                                                .count())
                                        .thenComparing(Memory::id, Comparator.reverseOrder()))
                        .toList();
        int max = Math.clamp(limit, 1, 12);
        var selected = new LinkedHashMap<Long, Memory>();
        // Preserve typed slots: repeated successful goals must not crowd out preferences or sourced
        // knowledge.
        ranked.stream()
                .filter(m -> m.kind().equals("PREFERENCE"))
                .limit(1)
                .forEach(m -> selected.put(m.id(), m));
        ranked.stream()
                .filter(m -> Set.of("SEMANTIC", "PROCEDURAL").contains(m.kind()))
                .limit(Math.min(2, max - selected.size()))
                .forEach(m -> selected.put(m.id(), m));
        for (var item : ranked) {
            if (selected.size() >= max) break;
            selected.putIfAbsent(item.id(), item);
        }
        return List.copyOf(selected.values());
    }

    /**
     * Include stale memories for inspection/forgetting, but never inject them into model context.
     */
    public List<Memory> manage(Long user, List<Long> scope) {
        var args = new ArrayList<Object>();
        args.add(user);
        args.addAll(scope);
        return jdbc
                .query(
                        "SELECT *,expires_at IS NULL OR expires_at>NOW(6) AS unexpired FROM (SELECT"
                            + " m.*,ROW_NUMBER() OVER(PARTITION BY kind ORDER BY update_time"
                            + " DESC,id DESC) AS slot_rank FROM agent_memory m WHERE user_id=? AND"
                            + " knowledge_base_id IN ("
                                + String.join(",", Collections.nCopies(scope.size(), "?"))
                                + ") AND status='ACTIVE') candidates WHERE slot_rank<=25 ORDER BY"
                                + " update_time DESC,id DESC",
                        (rs, n) -> {
                            List<Long> ids = new ArrayList<>();
                            for (var id : json.tree(rs.getString("scope_json")))
                                ids.add(id.asLong());
                            var m =
                                    new Memory(
                                            rs.getLong("id"),
                                            rs.getLong("knowledge_base_id"),
                                            rs.getString("kind"),
                                            rs.getString("title"),
                                            rs.getString("content"),
                                            ids,
                                            rs.getObject("source_note_id", Long.class),
                                            rs.getObject("source_revision", Integer.class),
                                            rs.getObject("origin_run_id", Long.class),
                                            rs.getInt("revision"),
                                            false);
                            return new Memory(
                                    m.id(),
                                    m.knowledgeBaseId(),
                                    m.kind(),
                                    m.title(),
                                    m.content(),
                                    m.scope(),
                                    m.sourceNoteId(),
                                    m.sourceRevision(),
                                    m.originRunId(),
                                    m.revision(),
                                    scope.containsAll(ids)
                                            && rs.getBoolean("unexpired")
                                            && fresh(user, m));
                        },
                        args.toArray())
                .stream()
                .filter(m -> scope.containsAll(m.scope()))
                .toList();
    }

    private boolean fresh(Long user, Memory memory) {
        for (Long kb : memory.scope())
            if (jdbc.queryForObject(
                            "SELECT COUNT(*) FROM knowledge_base WHERE id=? AND user_id=? AND"
                                    + " status='ACTIVE'",
                            Integer.class,
                            kb,
                            user)
                    != 1) return false;
        if (memory.sourceNoteId() == null) return true;
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM knowledge_note WHERE id=? AND user_id=? AND"
                                + " revision=? AND status='ACTIVE' AND knowledge_base_id=?",
                        Integer.class,
                        memory.sourceNoteId(),
                        user,
                        memory.sourceRevision(),
                        memory.knowledgeBaseId())
                == 1;
    }

    @Transactional
    public Memory put(Long user, Write request) {
        policy.content(request.title(), 160);
        policy.content(request.content(), 4000);
        if (!Set.of("PREFERENCE", "EPISODIC", "PROCEDURAL", "SEMANTIC").contains(request.kind()))
            throw new BusinessException(400, "未知记忆类型");
        if (jdbc.queryForObject(
                        "SELECT COUNT(*) FROM knowledge_base WHERE id=? AND user_id=? AND"
                                + " status='ACTIVE'",
                        Integer.class,
                        request.knowledgeBaseId(),
                        user)
                != 1) throw new BusinessException(403, "知识库无权访问");
        Integer version = null;
        if (request.sourceNoteId() != null) {
            var versions =
                    jdbc.query(
                            "SELECT revision FROM knowledge_note WHERE id=? AND user_id=? AND"
                                    + " knowledge_base_id=? AND status='ACTIVE'",
                            (rs, n) -> rs.getInt(1),
                            request.sourceNoteId(),
                            user,
                            request.knowledgeBaseId());
            if (versions.isEmpty()) throw new BusinessException(403, "记忆来源不可访问");
            version = versions.getFirst();
        }
        if (!"PREFERENCE".equals(request.kind()) && version == null)
            throw new BusinessException(400, "知识或经验记忆需要可追溯的笔记来源");
        long id = IdWorker.getId();
        String key = "manual:" + id;
        jdbc.update(
                "INSERT INTO"
                    + " agent_memory(id,user_id,knowledge_base_id,memory_key,kind,title,content,scope_json,source_note_id,source_revision,expires_at,create_time,update_time)"
                    + " VALUES (?,?,?,?,?,?,?,?,?,?,TIMESTAMPADD(DAY,?,NOW(6)),NOW(6),NOW(6))",
                id,
                user,
                request.knowledgeBaseId(),
                key,
                request.kind(),
                request.title().strip(),
                request.content().strip(),
                json.write(List.of(request.knowledgeBaseId())),
                request.sourceNoteId(),
                version,
                Math.clamp(request.ttlDays() == null ? 365 : request.ttlDays(), 1, 3650));
        return new Memory(
                id,
                request.knowledgeBaseId(),
                request.kind(),
                request.title(),
                request.content(),
                List.of(request.knowledgeBaseId()),
                request.sourceNoteId(),
                version,
                null,
                1,
                true);
    }

    public void rememberRun(Claim claim, String status) {
        if (claim.depth() > 0) return;
        var tools =
                claim.state().observations().stream()
                        .filter(o -> "SUCCESS".equals(o.status()))
                        .map(Observation::tool)
                        .distinct()
                        .toList();
        String content =
                "历史任务："
                        + AgentJson.clip(claim.goal(), 400)
                        + "；结果状态："
                        + status
                        + "；成功使用工具："
                        + String.join(",", tools)
                        + "。此为执行经验，事实结论须重新读取报告来源。";
        jdbc.update(
                "INSERT IGNORE INTO"
                    + " agent_memory(id,user_id,knowledge_base_id,memory_key,kind,title,content,scope_json,origin_run_id,expires_at,create_time,update_time)"
                    + " VALUES"
                    + " (?,?,?,?,'EPISODIC',?,?,?,?,TIMESTAMPADD(DAY,90,NOW(6)),NOW(6),NOW(6))",
                IdWorker.getId(),
                claim.userId(),
                claim.knowledgeBaseId(),
                "run:" + claim.id(),
                AgentJson.clip(claim.goal(), 160),
                content,
                json.write(claim.state().scope(claim.knowledgeBaseId())),
                claim.id());
    }

    public void forget(Long user, Long id) {
        if (jdbc.update(
                        "UPDATE agent_memory SET"
                            + " status='DELETED',revision=revision+1,update_time=NOW(6) WHERE id=?"
                            + " AND user_id=?",
                        id,
                        user)
                != 1) throw new BusinessException(404, "记忆不存在");
    }
}
