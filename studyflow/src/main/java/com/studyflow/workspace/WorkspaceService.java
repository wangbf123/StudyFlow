package com.studyflow.workspace;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.common.BusinessException;
import com.studyflow.document.*;
import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.task.TaskResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

/** Indexed, owner-scoped aggregates; no per-document count queries or unbounded entity loading. */
@Service
@RequiredArgsConstructor
public class WorkspaceService {
    private final KnowledgeBaseService knowledgeBases;
    private final JdbcTemplate jdbc;
    private final DocumentMapper documents;

    @Transactional(readOnly = true)
    public KnowledgeOverview overview(Long userId, Long knowledgeBaseId) {
        knowledgeBases.getOwnedActive(userId, knowledgeBaseId);
        long[] documentStats = jdbc.queryForObject("""
                SELECT COUNT(*),COALESCE(SUM(parse_status='SUCCESS'),0),
                  COALESCE(SUM(parse_status IN ('PENDING','PROCESSING')),0),COALESCE(SUM(parse_status='FAILED'),0),
                  COALESCE(SUM(file_size),0),COALESCE(SUM(summary_status='SUCCESS'),0)
                FROM document WHERE user_id=? AND knowledge_base_id=? AND parse_status<>'DELETED'
                """, (rs,row) -> new long[]{rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getLong(4),rs.getLong(5),rs.getLong(6)}, userId, knowledgeBaseId);
        long[] chunkStats = jdbc.queryForObject("""
                SELECT COUNT(*),COALESCE(SUM(c.embedding IS NOT NULL AND c.embedding<>''),0)
                FROM document_chunk c JOIN document d ON d.id=c.document_id AND d.generation=c.generation
                WHERE c.user_id=? AND c.knowledge_base_id=? AND d.user_id=? AND d.knowledge_base_id=? AND d.parse_status='SUCCESS'
                """, (rs,row) -> new long[]{rs.getLong(1),rs.getLong(2)}, userId,knowledgeBaseId,userId,knowledgeBaseId);
        Long evidence = jdbc.queryForObject("""
                SELECT COUNT(*) FROM qa_citation q JOIN document d ON d.id=q.document_id
                WHERE d.user_id=? AND d.knowledge_base_id=? AND d.parse_status<>'DELETED'
                  AND q.document_generation=d.generation AND q.quote IS NOT NULL
                """, Long.class,userId,knowledgeBaseId);
        List<KnowledgeOverview.FormatCount> formats = jdbc.query("""
                SELECT file_type,COUNT(*) FROM document WHERE user_id=? AND knowledge_base_id=? AND parse_status<>'DELETED'
                GROUP BY file_type ORDER BY COUNT(*) DESC,file_type LIMIT 12
                """, (rs,row) -> new KnowledgeOverview.FormatCount(rs.getString(1),rs.getLong(2)),userId,knowledgeBaseId);
        return new KnowledgeOverview(documentStats[0],documentStats[1],documentStats[2],documentStats[3],documentStats[4],
                chunkStats[0],chunkStats[1],evidence == null ? 0 : evidence,documentStats[5],formats,recentTasks(userId,knowledgeBaseId));
    }

    public DocumentPage documents(Long userId, Long knowledgeBaseId, Long beforeId, int size, String keyword, String status) {
        knowledgeBases.getOwnedActive(userId,knowledgeBaseId);
        int limit = Math.min(100,Math.max(1,size));
        if (keyword != null && keyword.length()>200) throw new BusinessException(400,"文件名筛选最多 200 字符");
        if (status != null && !status.isBlank() && !Set.of("PENDING","PROCESSING","SUCCESS","FAILED").contains(status)) {
            throw new BusinessException(400,"无效的文档状态");
        }
        var query = Wrappers.<DocumentEntity>lambdaQuery().eq(DocumentEntity::getUserId,userId)
                .eq(DocumentEntity::getKnowledgeBaseId,knowledgeBaseId).ne(DocumentEntity::getParseStatus,DocumentStatus.DELETED)
                .lt(beforeId != null,DocumentEntity::getId,beforeId)
                .like(keyword != null && !keyword.isBlank(),DocumentEntity::getOriginalFileName,keyword == null ? "" : keyword.trim())
                .eq(status != null && !status.isBlank(),DocumentEntity::getParseStatus,status)
                .orderByDesc(DocumentEntity::getId).last("LIMIT " + (limit+1));
        List<DocumentEntity> rows = documents.selectList(query);
        boolean more = rows.size()>limit;
        List<DocumentResponse> page = rows.stream().limit(limit).map(DocumentResponse::from).toList();
        return new DocumentPage(page,more ? page.getLast().id() : null,more);
    }

    public List<TaskResponse> recentTasks(Long userId, Long knowledgeBaseId) {
        knowledgeBases.getOwnedActive(userId,knowledgeBaseId);
        return jdbc.query("""
                SELECT t.* FROM parse_task t JOIN document d ON d.id=t.document_id AND d.generation=t.generation
                WHERE t.user_id=? AND d.user_id=? AND d.knowledge_base_id=? AND d.parse_status<>'DELETED'
                ORDER BY t.id DESC LIMIT 12
                """, (rs,row) -> new TaskResponse(rs.getLong("id"),rs.getString("task_no"),rs.getLong("document_id"),
                rs.getString("status"),rs.getString("stage"),rs.getLong("generation"),
                rs.getTimestamp("next_attempt_time")==null ? null : rs.getTimestamp("next_attempt_time").toLocalDateTime(),
                rs.getInt("retry_count"),rs.getInt("max_retry_count"),rs.getString("error_message"),
                rs.getTimestamp("started_time")==null ? null : rs.getTimestamp("started_time").toLocalDateTime(),
                rs.getTimestamp("finished_time")==null ? null : rs.getTimestamp("finished_time").toLocalDateTime(),
                rs.getTimestamp("create_time").toLocalDateTime()),userId,userId,knowledgeBaseId);
    }
}
