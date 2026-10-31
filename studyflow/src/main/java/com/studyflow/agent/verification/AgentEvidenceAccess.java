package com.studyflow.agent.verification;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.*;
import com.studyflow.rag.*;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Source-kind-aware access and revision checks, shared by verification and atomic artifact
 * publication.
 */
@Component
@RequiredArgsConstructor
public class AgentEvidenceAccess {
    private final JdbcTemplate jdbc;

    public boolean current(Long user, Long kb, Source source, boolean lock) {
        var chunk = source.chunk();
        String suffix = lock ? " FOR SHARE" : "";
        if ("NOTE".equals(chunk.source())) {
            var rows =
                    jdbc.query(
                            """
                            SELECT n.body,n.revision FROM knowledge_note n JOIN knowledge_base k ON k.id=n.knowledge_base_id
                            WHERE n.id=? AND n.user_id=? AND n.knowledge_base_id=? AND n.status='ACTIVE' AND k.user_id=? AND k.status='ACTIVE'
                            """
                                    + suffix,
                            (rs, n) -> Map.entry(rs.getString(1), (long) rs.getInt(2)),
                            chunk.chunkId(),
                            user,
                            kb,
                            user);
            return !rows.isEmpty()
                    && Objects.equals(rows.getFirst().getValue(), source.generation())
                    && EvidenceAnswerValidator.sha256(rows.getFirst().getKey())
                            .equals(source.contentHash())
                    && rows.getFirst().getKey().startsWith(chunk.content());
        }
        var rows =
                jdbc.query(
                        """
                        SELECT c.content,c.generation FROM document d JOIN document_chunk c ON c.document_id=d.id AND c.generation=d.generation
                        JOIN knowledge_base k ON k.id=d.knowledge_base_id
                        WHERE d.id=? AND c.id=? AND d.user_id=? AND d.knowledge_base_id=? AND c.user_id=? AND c.knowledge_base_id=? AND d.parse_status='SUCCESS' AND k.status='ACTIVE' AND k.user_id=?
                        """
                                + suffix,
                        (rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)),
                        chunk.documentId(),
                        chunk.chunkId(),
                        user,
                        kb,
                        user,
                        kb,
                        user);
        return !rows.isEmpty()
                && Objects.equals(rows.getFirst().getValue(), source.generation())
                && EvidenceAnswerValidator.sha256(rows.getFirst().getKey())
                        .equals(source.contentHash())
                && rows.getFirst().getKey().equals(chunk.content());
    }

    public EvidenceSpan refresh(Claim run, EvidenceSpan span) {
        for (var source : run.state().sources()) {
            if (Objects.equals(source.chunk().chunkId(), span.chunk().chunkId())
                    && Objects.equals(source.chunk().source(), span.chunk().source())
                    && run.state()
                            .scope(run.knowledgeBaseId())
                            .contains(source.chunk().knowledgeBaseId())
                    && EvidenceAnswerValidator.sha256(source.chunk().content())
                            .equals(span.contentHash())
                    && current(run.userId(), source.chunk().knowledgeBaseId(), source, false))
                return span.withGeneration(source.generation());
        }
        return null;
    }
}
