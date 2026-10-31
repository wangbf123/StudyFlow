package com.studyflow.notebook;

import org.neo4j.driver.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.util.*;

/** Rebuildable read model. IDs/scopes are server supplied; Cypher never comes from a model. */
@Configuration
@ConditionalOnProperty(name="studyflow.graph.enabled",havingValue="true")
public class Neo4jGraphStore {
    private final Driver driver;
    public Neo4jGraphStore(@Value("${studyflow.graph.uri:bolt://127.0.0.1:7687}") String uri,
                           @Value("${studyflow.graph.username:}") String username,
                           @Value("${studyflow.graph.password:}") String password) {
        driver=GraphDatabase.driver(uri,username.isBlank()?AuthTokens.none():AuthTokens.basic(username,password),
                Config.builder().withMaxConnectionPoolSize(8).withMaxTransactionRetryTime(5,java.util.concurrent.TimeUnit.SECONDS).withConnectionAcquisitionTimeout(5,java.util.concurrent.TimeUnit.SECONDS).withConnectionTimeout(5,java.util.concurrent.TimeUnit.SECONDS).build());
        driver.verifyConnectivity();
        try(var session=driver.session()) {session.executeWrite(tx->{tx.run("CREATE CONSTRAINT studyflow_note_id IF NOT EXISTS FOR (n:StudyFlowNote) REQUIRE n.id IS UNIQUE").consume();return true;});}
    }
    @jakarta.annotation.PreDestroy public void close(){driver.close();}
    public void project(Map<String,Object> note,List<Map<String,Object>> targets) {
        try(var session=driver.session()) {
            session.executeWrite(tx->{
                var accepted=tx.run("""
                        MERGE (n:StudyFlowNote {id:$id})
                        SET n._projectionLock=true
                        REMOVE n._projectionLock
                        WITH n WHERE coalesce(n.graphRevision,-1)<$graphRevision
                        SET n.userId=$userId,n.kbId=$kbId,n.title=$title,n.active=$active,n.graphRevision=$graphRevision
                        RETURN n.id AS id
                        """,note);
                if(!accepted.hasNext())return false;
                accepted.consume();
                tx.run("MATCH (n:StudyFlowNote {id:$id})-[r:WIKILINK]->() DELETE r",note).consume();
                if(Boolean.TRUE.equals(note.get("active")))tx.run("""
                        MATCH (n:StudyFlowNote {id:$id})
                        UNWIND $targets AS target
                        MERGE (t:StudyFlowNote {id:target.id})
                        MERGE (n)-[:WIKILINK]->(t)
                        """,Map.of("id",note.get("id"),"targets",targets)).consume();
                return true;
            });
        }
    }
    public Set<Long> neighbors(Long user,List<Long> scopes,Long focus) {
        try(var session=driver.session()) {
            return session.executeRead(tx->{
                var rows=tx.run("""
                        MATCH path=(n:StudyFlowNote {id:$id})-[:WIKILINK*0..2]-(m:StudyFlowNote)
                        WHERE all(v IN nodes(path) WHERE v.userId=$user AND v.kbId IN $scopes AND v.active=true)
                        RETURN DISTINCT m.id AS id LIMIT 101
                        """,Map.of("id",focus,"user",user,"scopes",scopes));
                Set<Long> ids=new HashSet<>();while(rows.hasNext())ids.add(rows.next().get("id").asLong());return ids;
            });
        }
    }
}
