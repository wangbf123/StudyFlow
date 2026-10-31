package com.studyflow.notebook;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.neo4j.driver.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="brain.neo4j.tests",matches="true")
class Neo4jGraphStoreTest {
    Neo4jGraphStore graph;long user=Math.abs(UUID.randomUUID().getLeastSignificantBits()/2),a=user-1,b=user-2,kb=user-3;
    @BeforeEach void setup(){graph=new Neo4jGraphStore("bolt://127.0.0.1:7687","","");}
    @AfterEach void close(){try(var driver=GraphDatabase.driver("bolt://127.0.0.1:7687",AuthTokens.none());var session=driver.session()){session.run("MATCH (n:StudyFlowNote) WHERE n.id IN $ids DETACH DELETE n",Map.of("ids",List.of(a,b))).consume();}graph.close();}
    Map<String,Object> note(long id,long rev,boolean active){return Map.of("id",id,"userId",user,"kbId",kb,"title","test","graphRevision",rev,"active",active);}
    @Test void olderProjectionCannotRestoreDeletedRelations(){
        graph.project(note(b,1,true),List.of());graph.project(note(a,2,true),List.of(Map.of("id",b)));assertEquals(Set.of(a,b),graph.neighbors(user,List.of(kb),a));
        graph.project(note(a,3,false),List.of());graph.project(note(a,2,true),List.of(Map.of("id",b)));assertTrue(graph.neighbors(user,List.of(kb),a).isEmpty());
    }
    @Test void projectionTraversalIsScopedToOwnerAndLibraries(){
        graph.project(note(b,1,true),List.of());graph.project(note(a,1,true),List.of(Map.of("id",b)));assertEquals(Set.of(a,b),graph.neighbors(user,List.of(kb),a));
        assertTrue(graph.neighbors(user+1,List.of(kb),a).isEmpty());assertTrue(graph.neighbors(user,List.of(kb+1),a).isEmpty());
    }
    @Test void concurrentOutOfOrderUpdatesKeepTheHighestRevision()throws Exception {
        graph.project(note(b,1,true),List.of());
        var versions=new ArrayList<Integer>();for(int i=1;i<=24;i++)versions.add(i);Collections.shuffle(versions,new Random(42));
        try(var pool=Executors.newFixedThreadPool(6)) {
            var jobs=versions.stream().<Callable<Boolean>>map(rev->()->{graph.project(note(a,rev,rev!=24),List.of(Map.of("id",b)));return true;}).toList();
            for(var future:pool.invokeAll(jobs))assertTrue(future.get());
        }
        assertTrue(graph.neighbors(user,List.of(kb),a).isEmpty());
    }
    @Test void concurrentSchemaInitializationUsesRetryableManagedTransactions()throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            List<Callable<Boolean>> jobs=List.of(()->{var store=new Neo4jGraphStore("bolt://127.0.0.1:7687","","");store.close();return true;},()->{var store=new Neo4jGraphStore("bolt://127.0.0.1:7687","","");store.close();return true;});
            for(var future:pool.invokeAll(jobs))assertTrue(future.get());
        }
    }
}
