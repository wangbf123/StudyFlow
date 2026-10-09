package com.studyflow.task;

import com.studyflow.document.DocumentEntity;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.studyflow.config.MybatisPlusMetaHandler;
import org.mybatis.spring.SqlSessionTemplate;
import com.studyflow.document.DocumentMapper;
import com.studyflow.embedding.EmbeddingClient;
import com.studyflow.embedding.EmbeddingProperties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/** Real InnoDB tests. Run only against a dedicated *_test schema, never an application schema. */
@EnabledIfSystemProperty(named = "pipeline.mysql.tests", matches = "true")
class PipelineStoreMySqlTest {
    static DriverManagerDataSource dataSource;
    JdbcTemplate jdbc;
    TransactionTemplate transactions;
    PipelineStore store;
    ParseTaskMapper tasks;
    DocumentMapper documents;

    @BeforeAll static void migrate() {
        String url = System.getProperty("pipeline.test.jdbc-url", "jdbc:mysql://localhost:3307/studyflow_pipeline_test?useSSL=false&allowPublicKeyRetrieval=true");
        if (!url.matches("jdbc:mysql://[^/]+/[a-zA-Z0-9_]+_test(?:\\?.*)?")) {
            throw new IllegalArgumentException("Pipeline integration tests require a dedicated *_test database");
        }
        dataSource = new DriverManagerDataSource(url,
                System.getProperty("pipeline.test.username", "studyflow"),
                System.getenv().getOrDefault("PIPELINE_TEST_DB_PASSWORD", "studyflow123"));
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").outOfOrder(true).load().migrate();
    }

    @BeforeEach void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        for (String table : List.of("pipeline_outbox", "parse_task", "document_chunk", "ai_summary", "document", "knowledge_base")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name,status,create_time,update_time) VALUES (1,1,'test','ACTIVE',NOW(),NOW())");
        jdbc.update("""
                INSERT INTO document(id,user_id,knowledge_base_id,original_file_name,file_type,file_size,file_md5,bucket,object_key,parse_status,generation,create_time,update_time)
                VALUES (1,1,1,'test.txt','txt',1,'d41d8cd98f00b204e9800998ecf8427e','test','test','PENDING',1,NOW(),NOW())
                """);
        // Use the production mapper: mocked JDBC reads hide transaction-local cache bugs.
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var global = GlobalConfigUtils.defaults();
        global.setMetaObjectHandler(new MybatisPlusMetaHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.addMapper(ParseTaskMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        try {
            tasks = new SqlSessionTemplate(factory.getObject()).getMapper(ParseTaskMapper.class);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
        documents = mock(DocumentMapper.class);
        when(documents.selectById(anyLong())).thenAnswer(invocation -> jdbc.queryForObject("SELECT * FROM document WHERE id=?", (rs,row) -> {
            DocumentEntity document = new DocumentEntity();
            document.setId(rs.getLong("id"));document.setUserId(rs.getLong("user_id"));document.setKnowledgeBaseId(rs.getLong("knowledge_base_id"));
            document.setGeneration(rs.getLong("generation"));document.setParseStatus(rs.getString("parse_status"));
            return document;
        }, (Object) invocation.getArgument(0)));
        EmbeddingClient embeddings = mock(EmbeddingClient.class);
        when(embeddings.model()).thenReturn("test-model");
        store = new PipelineStore(jdbc,transactions,tasks,documents,new PipelineProperties(),embeddings,new EmbeddingProperties());
    }

    ParseTaskEntity create() {
        return transactions.execute(status -> store.create(documents.selectById(1L), PipelineStage.PARSE));
    }

    @Test void claimReturnsFreshTokenDespiteEarlierSelectByIdInSameTransaction() {
        Long id = create().getId();
        ParseTaskEntity claimed = store.claim(id);
        assertNotNull(claimed);
        assertEquals(TaskStatus.PROCESSING, claimed.getStatus());
        assertNotNull(claimed.getClaimToken());
        assertEquals(jdbc.queryForObject("SELECT claim_token FROM parse_task WHERE id=?", String.class, id),
                claimed.getClaimToken());
        assertTrue(store.checkpoint(claimed, document -> {}));
        assertEquals("EMBED", tasks.selectById(id).getStage());
    }

    @Test void documentTaskAndOutboxShareRollbackBoundary() {
        assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
            store.create(documents.selectById(1L), PipelineStage.PARSE);
            jdbc.update("UPDATE document SET summary='should rollback' WHERE id=1");
            throw new IllegalStateException("simulate failure before commit");
        }));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM parse_task", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_outbox", Integer.class));
        assertNull(jdbc.queryForObject("SELECT summary FROM document WHERE id=1", String.class));
    }

    @Test void onlyOneConcurrentWorkerClaimsAndExpiredTokenCannotPublish() throws Exception {
        Long id = create().getId();
        List<ParseTaskEntity> claims;
        try (var executor = Executors.newFixedThreadPool(12)) {
            List<Callable<ParseTaskEntity>> work = java.util.stream.IntStream.range(0,24)
                    .mapToObj(i -> (Callable<ParseTaskEntity>) () -> store.claim(id)).toList();
            claims = executor.invokeAll(work).stream().map(f -> { try { return f.get(); } catch (Exception ex) { throw new RuntimeException(ex); } }).toList();
        }
        assertEquals(1, claims.stream().filter(java.util.Objects::nonNull).count());
        ParseTaskEntity stale = claims.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow();
        jdbc.update("UPDATE parse_task SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?", id);
        store.recover();
        ParseTaskEntity replacement = store.claim(id);
        assertNotNull(replacement);
        assertNotEquals(stale.getClaimToken(), replacement.getClaimToken());
        assertFalse(store.checkpoint(stale, document -> fail("stale worker must not reach checkpoint callback")));
        assertTrue(store.checkpoint(replacement, document -> jdbc.update("UPDATE document SET summary='fresh' WHERE id=1")));
        assertEquals("EMBED", tasks.selectById(id).getStage());
        assertEquals("fresh", jdbc.queryForObject("SELECT summary FROM document WHERE id=1", String.class));
    }

    @Test void checkpointAndNextStageEventAreAtomic() {
        Long id = create().getId();
        ParseTaskEntity claim = store.claim(id);
        assertThrows(IllegalStateException.class, () -> store.checkpoint(claim, document -> {
            jdbc.update("UPDATE document SET summary='partial' WHERE id=1");
            throw new IllegalStateException("simulate checkpoint write failure");
        }));
        assertNull(jdbc.queryForObject("SELECT summary FROM document WHERE id=1", String.class));
        assertEquals("PARSE",tasks.selectById(id).getStage());
        assertTrue(store.checkpoint(claim, document -> {}));
        assertEquals("PENDING",tasks.selectById(id).getStatus());
        assertEquals("EMBED",tasks.selectById(id).getStage());
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM pipeline_outbox WHERE task_id=? AND stage='EMBED' AND status='PENDING'",Integer.class,id));
    }

    @Test void restartRecoversExecutorWakeupThatWasSentButNeverRan() {
        Long id = create().getId();
        jdbc.update("UPDATE pipeline_outbox SET status='SENT',update_time=TIMESTAMPADD(SECOND,-11,NOW(6)) WHERE task_id=?",id);
        store.recover();
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM pipeline_outbox WHERE task_id=?",String.class,id));
        assertNotNull(store.claim(id));
    }

    @Test void failedDeliveryRetainsOutboxAndUsesFencedConfirmation() {
        Long id = create().getId();
        Long event = store.dueOutbox().getFirst();
        assertEquals(id,store.claimOutbox(event,"first"));
        store.sendFailed(event,"first",new IllegalStateException("broker unavailable"));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM pipeline_outbox WHERE id=?",String.class,event));
        jdbc.update("UPDATE pipeline_outbox SET next_attempt_time=NOW(6) WHERE id=?",event);
        assertEquals(id,store.claimOutbox(event,"second"));
        store.sent(event,"first");
        assertEquals("PROCESSING",jdbc.queryForObject("SELECT status FROM pipeline_outbox WHERE id=?",String.class,event));
        store.sent(event,"second");
        assertEquals("SENT",jdbc.queryForObject("SELECT status FROM pipeline_outbox WHERE id=?",String.class,event));
    }

    @Test void rebuildAndDeleteFenceAlreadyRunningWorkers() {
        Long id = create().getId();
        ParseTaskEntity claimed = store.claim(id);
        jdbc.update("UPDATE document SET generation=2,parse_status='PENDING' WHERE id=1");
        assertFalse(store.checkpoint(claimed, document -> fail("old generation must not publish")));
        assertEquals("CANCELLED",tasks.selectById(id).getStatus());
        Long newer = create().getId();
        ParseTaskEntity active = store.claim(newer);
        jdbc.update("UPDATE document SET parse_status='DELETED' WHERE id=1");
        assertFalse(store.checkpoint(active, document -> fail("deleted document must not publish")));
        ParseTaskEntity cleanup = transactions.execute(status -> store.create(documents.selectById(1L),PipelineStage.CLEANUP));
        ParseTaskEntity cleanupClaim = store.claim(cleanup.getId());
        assertNotNull(cleanupClaim);
        assertTrue(store.checkpoint(cleanupClaim, document -> {}));
        assertEquals("DELETED",jdbc.queryForObject("SELECT parse_status FROM document WHERE id=1",String.class));
    }

    @Test void exhaustedLeaseRecoveryStopsRetries() {
        Long id = create().getId();
        ParseTaskEntity claimed = store.claim(id);
        jdbc.update("UPDATE parse_task SET retry_count=max_retry_count-1,lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?",id);
        store.recover();
        assertEquals("FAILED",tasks.selectById(id).getStatus());
        assertEquals("FAILED",jdbc.queryForObject("SELECT parse_status FROM document WHERE id=1",String.class));
        assertNull(store.claim(id));
        assertFalse(store.renew(claimed));
    }
    @Test void onlyRetiredGenerationsAreEligibleForPhysicalIndexCleanup() {
        Long id=create().getId();
        jdbc.update("UPDATE parse_task SET stage='INDEX',status='SUCCESS' WHERE id=?",id);
        jdbc.update("UPDATE document SET parse_status='SUCCESS' WHERE id=1");
        assertTrue(store.retiredIndexes().isEmpty());
        jdbc.update("UPDATE document SET generation=2,parse_status='PENDING' WHERE id=1");
        List<PipelineStore.RetiredIndex> retired=store.retiredIndexes();
        assertEquals(1,retired.size());assertEquals(1L,retired.getFirst().generation());
        assertEquals(1L,retired.getFirst().documentId());
    }

}
