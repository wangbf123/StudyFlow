package com.studyflow.notebook;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.util.*;

/** At-least-once outbox delivery; per-note graphRevision fences older projections. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name="studyflow.graph.enabled",havingValue="true")
public class GraphProjectionWorker {
    private final JdbcTemplate jdbc;
    private final Neo4jGraphStore graph;
    @Scheduled(fixedDelayString="${studyflow.graph.delay-ms:1500}")
    public void dispatch() {
        var ids=jdbc.query("SELECT id FROM knowledge_graph_outbox WHERE (status='PENDING' AND next_attempt_at<=NOW(6)) OR (status='SENDING' AND lease_until<NOW(6)) ORDER BY id LIMIT 20",(rs,n)->rs.getLong(1));
        for(Long id:ids) {
            String token=UUID.randomUUID().toString();
            int claimed=jdbc.update("UPDATE knowledge_graph_outbox SET status='SENDING',claim_token=?,lease_until=TIMESTAMPADD(SECOND,30,NOW(6)),attempts=attempts+1 WHERE id=? AND ((status='PENDING' AND next_attempt_at<=NOW(6)) OR (status='SENDING' AND lease_until<NOW(6)))",token,id);
            if(claimed!=1)continue;
            try {
                Long noteId=jdbc.queryForObject("SELECT note_id FROM knowledge_graph_outbox WHERE id=?",Long.class,id);
                var rows=jdbc.query("""
                        SELECT n.*,k.status AS kb_status FROM knowledge_note n JOIN knowledge_base k ON k.id=n.knowledge_base_id WHERE n.id=?
                        """,(rs,n)-> {
                            Map<String,Object> values=new HashMap<>();values.put("id",noteId);values.put("userId",rs.getLong("user_id"));values.put("kbId",rs.getLong("knowledge_base_id"));
                            values.put("title",rs.getString("title"));values.put("active","ACTIVE".equals(rs.getString("status"))&&"ACTIVE".equals(rs.getString("kb_status")));values.put("graphRevision",rs.getLong("graph_revision"));return values;
                        },noteId);
                if(!rows.isEmpty()) {
                    var note=rows.getFirst();
                    var targets=jdbc.query("""
                            SELECT DISTINCT t.id FROM knowledge_note_link l JOIN knowledge_note t ON t.id=l.target_id
                            JOIN knowledge_base k ON k.id=t.knowledge_base_id
                            WHERE l.source_id=? AND t.status='ACTIVE' AND t.user_id=? AND k.status='ACTIVE' AND k.user_id=?
                            """,(rs,n)->Map.<String,Object>of("id",rs.getLong(1)),noteId,note.get("userId"),note.get("userId"));
                    graph.project(note,targets);
                }
                jdbc.update("UPDATE knowledge_graph_outbox SET status='SENT',claim_token=NULL,lease_until=NULL WHERE id=? AND claim_token=?",id,token);
            } catch(Exception ex) {
                jdbc.update("UPDATE knowledge_graph_outbox SET status='PENDING',next_attempt_at=TIMESTAMPADD(SECOND,LEAST(300,attempts*5),NOW(6)),claim_token=NULL,lease_until=NULL WHERE id=? AND claim_token=?",id,token);
            }
        }
    }
}
