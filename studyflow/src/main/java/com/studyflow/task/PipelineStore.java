package com.studyflow.task;

import com.studyflow.document.DocumentEntity;
import com.studyflow.embedding.EmbeddingClient;
import com.studyflow.embedding.EmbeddingProperties;
import com.studyflow.document.DocumentMapper;
import com.studyflow.document.DocumentStatus;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/** Database is the authority; MQ messages are only wakeups and may be duplicated or lost. */
@Service
@RequiredArgsConstructor
public class PipelineStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ParseTaskMapper tasks;
    private final DocumentMapper documents;
    private final PipelineProperties properties;
    private final EmbeddingClient embeddings;
    private final EmbeddingProperties embeddingProperties;

    public ParseTaskEntity create(DocumentEntity document, PipelineStage stage) {
        if (stage == PipelineStage.SUMMARY) {
            jdbc.update("UPDATE document SET summary_status='PENDING' WHERE id=? AND generation=?", document.getId(), document.getGeneration());
        }
        ParseTaskEntity task = new ParseTaskEntity();
        task.setTaskNo("PT" + UUID.randomUUID().toString().replace("-", ""));
        task.setDocumentId(document.getId());
        task.setUserId(document.getUserId());
        task.setGeneration(document.getGeneration() == null ? 1L : document.getGeneration());
        task.setStage(stage.name());
        task.setEmbeddingModel(embeddings.model());
        task.setEmbeddingDimension(embeddingProperties.getDimension());
        task.setStatus(TaskStatus.PENDING);
        task.setRetryCount(0);
        task.setMaxRetryCount(5);
        tasks.insert(task);
        enqueue(task.getId(), stage.name());
        return task;
    }

    /** Must run inside the same transaction as the document/stage checkpoint. */
    public void enqueue(Long taskId, String stage) {
        jdbc.update("""
                INSERT INTO pipeline_outbox(task_id,stage) VALUES (?,?)
                ON DUPLICATE KEY UPDATE status='PENDING', claim_token=NULL, lease_until=NULL,
                  next_attempt_time=NOW(6), error_message=NULL, update_time=NOW(6)
                """, taskId, stage);
    }

    public ParseTaskEntity claim(Long taskId) {
        return transactions.execute(status -> {
            ParseTaskEntity candidate = tasks.selectById(taskId);
            if (candidate == null) return null;
            jdbc.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE", Long.class, candidate.getDocumentId());
            String token = UUID.randomUUID().toString();
            int updated = jdbc.update("""
                    UPDATE parse_task t JOIN document d ON d.id=t.document_id
                      JOIN knowledge_base k ON k.id=d.knowledge_base_id
                    SET t.status='PROCESSING',t.claim_token=?,t.lease_until=TIMESTAMPADD(SECOND,?,NOW(6)),
                      t.started_time=NOW(),t.finished_time=NULL,t.error_message=NULL,t.update_time=NOW()
                    WHERE t.id=? AND t.status IN ('PENDING','RETRYING') AND t.next_attempt_time<=NOW(6)
                      AND t.generation=d.generation AND
                      ((t.stage='CLEANUP' AND d.parse_status='DELETED') OR
                       (t.stage<>'CLEANUP' AND d.parse_status<>'DELETED' AND k.status='ACTIVE'))
                    """, token, properties.leaseSeconds(), taskId);
            if (updated == 0) return null;
            // JDBC updates do not invalidate MyBatis' transaction-local selectById cache.
            // Read through a token-qualified query so the returned object contains this lease.
            ParseTaskEntity task = tasks.selectOne(Wrappers.<ParseTaskEntity>lambdaQuery()
                    .eq(ParseTaskEntity::getId, taskId).eq(ParseTaskEntity::getClaimToken, token));
            if (PipelineStage.SUMMARY.name().equals(task.getStage())) {
                jdbc.update("UPDATE document SET summary_status='PROCESSING' WHERE id=? AND generation=?", task.getDocumentId(), task.getGeneration());
            } else if (!PipelineStage.CLEANUP.name().equals(task.getStage())) {
                jdbc.update("UPDATE document SET parse_status='PROCESSING',update_time=NOW() WHERE id=? AND generation=? AND parse_status<>'DELETED'",
                        task.getDocumentId(), task.getGeneration());
            }
            return task;
        });
    }

    public boolean renew(ParseTaskEntity task) {
        return jdbc.update("""
                UPDATE parse_task SET lease_until=TIMESTAMPADD(SECOND,?,NOW(6)),update_time=NOW()
                WHERE id=? AND claim_token=? AND status='PROCESSING' AND lease_until>NOW(6)
                """, properties.leaseSeconds(), task.getId(), task.getClaimToken()) == 1;
    }

    /** Fence stale workers and serialize checkpoint publication with rebuild/deletion. */
    public boolean checkpoint(ParseTaskEntity claimed, Consumer<DocumentEntity> work) {
        return commitCheckpoint(claimed, work, true);
    }

    /** Persist one bounded batch without advancing the durable stage. */
    public boolean checkpointBatch(ParseTaskEntity claimed, Consumer<DocumentEntity> work) {
        return commitCheckpoint(claimed, work, false);
    }

    private boolean commitCheckpoint(ParseTaskEntity claimed, Consumer<DocumentEntity> work, boolean advance) {
        Boolean committed = transactions.execute(status -> {
            jdbc.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE", Long.class, claimed.getDocumentId());
            List<Long> locked = jdbc.queryForList("""
                    SELECT id FROM parse_task WHERE id=? AND status='PROCESSING' AND claim_token=?
                      AND stage=? AND lease_until>NOW(6) FOR UPDATE
                    """, Long.class, claimed.getId(), claimed.getClaimToken(), claimed.getStage());
            if (locked.isEmpty()) return false;
            DocumentEntity document = documents.selectById(claimed.getDocumentId());
            boolean cleanup = PipelineStage.CLEANUP.name().equals(claimed.getStage());
            List<String> kbStatuses = document == null ? List.of() : jdbc.queryForList(
                    "SELECT status FROM knowledge_base WHERE id=? FOR UPDATE", String.class, document.getKnowledgeBaseId());
            if (document == null || !claimed.getGeneration().equals(document.getGeneration())
                    || (cleanup ? !DocumentStatus.DELETED.equals(document.getParseStatus())
                    : DocumentStatus.DELETED.equals(document.getParseStatus()) || !kbStatuses.contains("ACTIVE"))) {
                jdbc.update("UPDATE parse_task SET status='CANCELLED',claim_token=NULL,lease_until=NULL,finished_time=NOW(),update_time=NOW() WHERE id=?", claimed.getId());
                return false;
            }
            work.accept(document);
            if (!advance) return true;
            PipelineStage next = PipelineStage.valueOf(claimed.getStage()).next();
            if (next == null) {
                jdbc.update("""
                        UPDATE parse_task SET status='SUCCESS',claim_token=NULL,lease_until=NULL,
                          finished_time=NOW(),error_message=NULL,update_time=NOW() WHERE id=?
                        """, claimed.getId());
                if (PipelineStage.SUMMARY.name().equals(claimed.getStage())) {
                    jdbc.update("UPDATE document SET summary_status='SUCCESS',update_time=NOW() WHERE id=?", document.getId());
                } else if (!cleanup) {
                    jdbc.update("UPDATE document SET parse_status='SUCCESS',update_time=NOW() WHERE id=?", document.getId());
                    // Index readiness never waits on a model-generated derived summary.
                    create(document, PipelineStage.SUMMARY);
                }
            } else {
                jdbc.update("""
                        UPDATE parse_task SET stage=?,status='PENDING',claim_token=NULL,lease_until=NULL,
                          retry_count=0,next_attempt_time=NOW(6),error_message=NULL,update_time=NOW() WHERE id=?
                        """, next.name(), claimed.getId());
                enqueue(claimed.getId(), next.name());
            }
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    public void fail(ParseTaskEntity task, Exception error) {
        transactions.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE", Long.class, task.getDocumentId());
            List<Long> locked = jdbc.queryForList("SELECT id FROM parse_task WHERE id=? AND claim_token=? AND status='PROCESSING' FOR UPDATE",
                    Long.class, task.getId(), task.getClaimToken());
            if (locked.isEmpty()) return;
            int attempt = task.getRetryCount() + 1;
            String state = attempt >= task.getMaxRetryCount() ? TaskStatus.FAILED : TaskStatus.RETRYING;
            String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            jdbc.update("""
                    UPDATE parse_task SET status=?,retry_count=?,error_message=?,claim_token=NULL,lease_until=NULL,
                      next_attempt_time=TIMESTAMPADD(SECOND,?,NOW(6)),finished_time=NOW(),update_time=NOW() WHERE id=?
                    """, state, attempt, message.substring(0, Math.min(1000, message.length())),
                    properties.retryDelay(attempt), task.getId());
            if (PipelineStage.SUMMARY.name().equals(task.getStage())) {
                jdbc.update("UPDATE document SET summary_status=?,update_time=NOW() WHERE id=? AND generation=? AND parse_status<>'DELETED'",
                        state, task.getDocumentId(), task.getGeneration());
            } else if (!PipelineStage.CLEANUP.name().equals(task.getStage())) jdbc.update("""
                    UPDATE document SET parse_status=?,update_time=NOW() WHERE id=? AND generation=? AND parse_status<>'DELETED'
                    """, TaskStatus.FAILED.equals(state) ? DocumentStatus.FAILED : DocumentStatus.PENDING,
                    task.getDocumentId(), task.getGeneration());
        });
    }

    /** Bounded recovery locks document before task, matching worker/rebuild lock order. */
    public void recover() {
        List<long[]> due = jdbc.query("""
                SELECT t.id,t.document_id FROM parse_task t
                LEFT JOIN document d ON d.id=t.document_id LEFT JOIN knowledge_base k ON k.id=d.knowledge_base_id
                WHERE t.status IN ('PENDING','RETRYING','PROCESSING') AND
                  (d.id IS NULL OR t.generation<>d.generation OR
                   (t.stage<>'CLEANUP' AND (d.parse_status='DELETED' OR k.status<>'ACTIVE' OR k.id IS NULL)) OR
                   (t.status='PROCESSING' AND t.lease_until<=NOW(6)))
                ORDER BY t.update_time,t.id LIMIT ?
                """, (rs,row) -> new long[]{rs.getLong(1),rs.getLong(2)}, properties.batchSize());
        for (long[] candidate : due) transactions.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM document WHERE id=? FOR UPDATE", Long.class, candidate[1]);
            var current = jdbc.query("""
                    SELECT t.stage,t.generation,t.retry_count,t.max_retry_count,t.status,
                      (t.lease_until<=NOW(6)),d.generation,d.parse_status,k.status
                    FROM parse_task t LEFT JOIN document d ON d.id=t.document_id
                    LEFT JOIN knowledge_base k ON k.id=d.knowledge_base_id
                    WHERE t.id=? AND t.status IN ('PENDING','RETRYING','PROCESSING') FOR UPDATE
                    """, (rs,row) -> new RecoveryState(rs.getString(1),rs.getLong(2),rs.getInt(3),rs.getInt(4),
                            rs.getString(5),rs.getBoolean(6),rs.getObject(7,Long.class),rs.getString(8),rs.getString(9)), candidate[0]);
            if (current.isEmpty()) return;
            RecoveryState task = current.getFirst();
            boolean cancelled = task.documentGeneration() == null || task.generation() != task.documentGeneration()
                    || (!"CLEANUP".equals(task.stage()) && ("DELETED".equals(task.documentStatus()) || !"ACTIVE".equals(task.kbStatus())));
            if (cancelled) {
                jdbc.update("UPDATE parse_task SET status='CANCELLED',claim_token=NULL,lease_until=NULL,finished_time=NOW(),update_time=NOW() WHERE id=?", candidate[0]);
            } else if ("PROCESSING".equals(task.status()) && task.expired()) {
                String next = task.retries() + 1 >= task.maxRetries() ? "FAILED" : "RETRYING";
                jdbc.update("""
                        UPDATE parse_task SET status=?,retry_count=retry_count+1,claim_token=NULL,lease_until=NULL,
                          next_attempt_time=NOW(6),error_message='Worker lease expired; checkpoint retained',update_time=NOW() WHERE id=?
                        """, next, candidate[0]);
                if ("SUMMARY".equals(task.stage())) {
                    jdbc.update("UPDATE document SET summary_status=?,update_time=NOW() WHERE id=?", next, candidate[1]);
                } else if (!"CLEANUP".equals(task.stage())) {
                    jdbc.update("UPDATE document SET parse_status=?,update_time=NOW() WHERE id=?",
                            "FAILED".equals(next) ? "FAILED" : "PENDING", candidate[1]);
                }
            }
        });
        jdbc.update("""
                INSERT INTO pipeline_outbox(task_id,stage)
                SELECT id,stage FROM parse_task WHERE status IN ('PENDING','RETRYING') AND next_attempt_time<=NOW(6)
                ORDER BY next_attempt_time,id LIMIT ?
                ON DUPLICATE KEY UPDATE
                  status=IF(pipeline_outbox.status='SENT' AND pipeline_outbox.update_time<TIMESTAMPADD(SECOND,-10,NOW(6)), 'PENDING',pipeline_outbox.status)
                """, properties.batchSize());
    }

    private record RecoveryState(String stage,long generation,int retries,int maxRetries,String status,
                                 boolean expired,Long documentGeneration,String documentStatus,String kbStatus) {}

    public record RetiredIndex(Long taskId, Long documentId, Long generation) {}

    public List<RetiredIndex> retiredIndexes() {
        return jdbc.query("""
                SELECT t.id,t.document_id,t.generation FROM parse_task t
                JOIN document d ON d.id=t.document_id LEFT JOIN knowledge_base k ON k.id=d.knowledge_base_id
                WHERE t.stage='INDEX' AND t.status IN ('SUCCESS','CANCELLED','FAILED')
                  AND (d.generation<>t.generation OR d.parse_status='DELETED' OR k.status<>'ACTIVE')
                ORDER BY t.update_time,t.id LIMIT ?
                """, (rs,row) -> new RetiredIndex(rs.getLong(1),rs.getLong(2),rs.getLong(3)), properties.batchSize());
    }

    public void reconciled(RetiredIndex index) {
        jdbc.update("UPDATE parse_task SET update_time=NOW() WHERE id=?", index.taskId());
    }

    public List<Long> dueOutbox() {
        return jdbc.queryForList("""
                SELECT id FROM pipeline_outbox WHERE
                  (status='PENDING' AND next_attempt_time<=NOW(6)) OR (status='PROCESSING' AND lease_until<=NOW(6))
                ORDER BY id LIMIT ?
                """, Long.class, properties.batchSize());
    }

    public Long claimOutbox(Long id, String token) {
        int updated = jdbc.update("""
                UPDATE pipeline_outbox SET status='PROCESSING',claim_token=?,lease_until=TIMESTAMPADD(SECOND,?,NOW(6)),
                  attempt_count=attempt_count+1,update_time=NOW(6)
                WHERE id=? AND ((status='PENDING' AND next_attempt_time<=NOW(6)) OR (status='PROCESSING' AND lease_until<=NOW(6)))
                """, token, properties.leaseSeconds(), id);
        return updated == 0 ? null : jdbc.queryForObject("SELECT task_id FROM pipeline_outbox WHERE id=?", Long.class, id);
    }

    public void sent(Long id, String token) {
        jdbc.update("UPDATE pipeline_outbox SET status='SENT',claim_token=NULL,lease_until=NULL,error_message=NULL,update_time=NOW(6) WHERE id=? AND claim_token=?",
                id, token);
    }

    public void sendFailed(Long id, String token, Exception error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        jdbc.update("""
                UPDATE pipeline_outbox SET status='PENDING',claim_token=NULL,lease_until=NULL,
                  next_attempt_time=TIMESTAMPADD(SECOND,LEAST(POW(2,LEAST(attempt_count,8)),?),NOW(6)),
                  error_message=?,update_time=NOW(6) WHERE id=? AND claim_token=?
                """, Math.max(1, properties.getMaxRetryDelaySeconds()),
                message.substring(0, Math.min(1000, message.length())), id, token);
    }
}
