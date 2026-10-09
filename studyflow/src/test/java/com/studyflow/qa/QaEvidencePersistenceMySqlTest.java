package com.studyflow.qa;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.studyflow.ai.AiClient;
import com.studyflow.ai.AiProperties;
import com.studyflow.common.BusinessException;
import com.studyflow.config.MybatisPlusMetaHandler;
import com.studyflow.document.DocumentChunkMapper;
import com.studyflow.document.DocumentMapper;
import com.studyflow.embedding.EmbeddingClient;
import com.studyflow.embedding.EmbeddingProperties;
import com.studyflow.kb.KnowledgeBaseMapper;
import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.rag.*;
import com.studyflow.task.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Actual MySQL locks, production MyBatis mappers and migrations, isolated random database. */
@EnabledIfSystemProperty(named = "evidence.mysql.tests", matches = "true")
class QaEvidencePersistenceMySqlTest {
    private static final String DATABASE = "studyflow_evidence_" + UUID.randomUUID().toString().replace("-", "") + "_test";
    private static final String CONTENT = "Redis uses an atomic Lua script for rate limiting.";
    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate sql;
    private static DataSourceTransactionManager manager;
    private static TransactionTemplate transactions;
    private static String adminUrl;
    private static String username;
    private static String password;
    private static boolean created;
    private QaSessionEntity session;
    private QaService qa;
    private QaMessageMapper messages;
    private QaCitationMapper citations;
    private QaClaimMapper claims;

    @BeforeAll
    static void migrate() throws Exception {
        adminUrl = System.getProperty("evidence.test.admin-url",
                "jdbc:mysql://127.0.0.1:3307/?serverTimezone=UTC&allowPublicKeyRetrieval=true&useSSL=false");
        username = System.getProperty("evidence.test.username", "root");
        password = System.getenv("EVIDENCE_TEST_DB_PASSWORD");
        assertNotNull(password, "Provide EVIDENCE_TEST_DB_PASSWORD securely to enable real database tests");
        try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE `" + DATABASE + "` CHARACTER SET utf8mb4");
            created = true;
        }
        int databaseStart = adminUrl.indexOf('/', "jdbc:mysql://".length());
        int queryStart = adminUrl.indexOf('?', databaseStart);
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(adminUrl.substring(0, databaseStart + 1) + DATABASE
                + (queryStart < 0 ? "" : adminUrl.substring(queryStart)));
        pool.setUsername(username);
        pool.setPassword(password);
        pool.setMaximumPoolSize(8);
        pool.setConnectionInitSql("SET time_zone = '+00:00'");
        dataSource = new HikariDataSource(pool);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        // Install fill metadata before mapper registration initializes TableInfo.
        var global = GlobalConfigUtils.defaults();
        global.setMetaObjectHandler(new MybatisPlusMetaHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        for (Class<?> mapper : List.of(QaSessionMapper.class, QaMessageMapper.class, QaCitationMapper.class,
                QaClaimMapper.class, DocumentMapper.class, DocumentChunkMapper.class,
                KnowledgeBaseMapper.class, ParseTaskMapper.class)) configuration.addMapper(mapper);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        sql = new SqlSessionTemplate(factory.getObject());
        manager = new DataSourceTransactionManager(dataSource);
        transactions = new TransactionTemplate(manager);
    }

    @AfterAll
    static void drop() throws Exception {
        if (dataSource != null) dataSource.close();
        if (created) try (Connection connection = DriverManager.getConnection(adminUrl, username, password);
                          Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE `" + DATABASE + "`");
        }
    }

    @BeforeEach
    void seed() {
        for (String table : List.of("qa_citation", "qa_claim", "qa_message", "qa_session", "pipeline_outbox",
                "parse_task", "document_chunk", "document", "knowledge_base")) jdbc.update("DELETE FROM " + table);
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name,status,create_time,update_time) VALUES(1,1,'test','ACTIVE',NOW(),NOW())");
        jdbc.update("""
                INSERT INTO document(id,user_id,knowledge_base_id,original_file_name,file_type,file_size,file_md5,
                  bucket,object_key,parse_status,generation,create_time,update_time)
                VALUES(20,1,1,'guide.txt','txt',50,'d41d8cd98f00b204e9800998ecf8427e','test','test','SUCCESS',1,NOW(),NOW())
                """);
        jdbc.update("""
                INSERT INTO document_chunk(id,document_id,knowledge_base_id,user_id,chunk_index,content,char_count,generation,create_time)
                VALUES(30,20,1,1,0,?,?,1,NOW())
                """, CONTENT, CONTENT.length());
        jdbc.update("INSERT INTO qa_session(id,user_id,knowledge_base_id,title,status,create_time,update_time) VALUES(10,1,1,'test','ACTIVE',NOW(),NOW())");
        session = sql.getMapper(QaSessionMapper.class).selectById(10L);
        messages = sql.getMapper(QaMessageMapper.class);
        citations = sql.getMapper(QaCitationMapper.class);
        claims = sql.getMapper(QaClaimMapper.class);
        qa = service(messages, citations);
    }

    @Test
    void messageClaimsAndEvidenceCommitTogetherAndRemainVisibleInHistory() throws Exception {
        persist(qa, answer());
        assertRows(1, 1, 1);
        QaMessageResponse history = qa.listMessages(1L, 10L).getFirst();
        assertEquals("SUPPORTED", history.answerStatus());
        assertEquals("MODEL_VERIFIED", history.verificationMode());
        assertEquals(1, history.claims().size());
        assertEquals(1, history.claims().getFirst().evidence().size());
        assertEquals(CONTENT, history.claims().getFirst().evidence().getFirst().quote());
        QaCitationDetailResponse detail = qa.getCitationChunk(1L, history.citations().getFirst().citationId());
        assertEquals("CURRENT", detail.evidenceState());
        assertEquals(CONTENT, detail.content().substring(detail.charStart(), detail.charEnd()));
        assertThrows(BusinessException.class, () -> qa.listMessages(99L, 10L));
        assertThrows(BusinessException.class, () -> qa.getCitationChunk(99L, detail.citationId()));
    }

    @Test
    void failedEvidenceInsertRollsBackMessageAndClaims() {
        QaCitationMapper failing = mock(QaCitationMapper.class, delegatesTo(citations));
        doThrow(new IllegalStateException("injected citation write failure")).when(failing).insert(any(QaCitationEntity.class));
        QaService failingService = service(messages, failing);
        assertThrows(IllegalStateException.class, () -> persist(failingService, answer()));
        assertRows(0, 0, 0);
    }

    @Test
    void rebuildBeforePublicationRejectsTheOldGeneration() {
        transactions.executeWithoutResult(status -> {
            jdbc.queryForList("SELECT id FROM document WHERE id=20 FOR UPDATE", Long.class);
            jdbc.update("UPDATE document SET generation=2,parse_status='PENDING' WHERE id=20");
        });
        assertThrows(BusinessException.class, () -> persist(qa, answer()));
        assertRows(0, 0, 0);
    }

    @Test
    void deletedKnowledgeBaseCannotPublishOrExposeExistingEvidence() throws Exception {
        persist(qa, answer());
        Long citationId = jdbc.queryForObject("SELECT id FROM qa_citation", Long.class);
        Long messageId = jdbc.queryForObject("SELECT id FROM qa_message", Long.class);
        jdbc.update("UPDATE knowledge_base SET status='DELETED' WHERE id=1");
        assertThrows(BusinessException.class, () -> persist(qa, answer()));
        assertThrows(BusinessException.class, () -> qa.listMessages(1L, 10L));
        assertThrows(BusinessException.class, () -> qa.listCitations(1L, messageId));
        assertThrows(BusinessException.class, () -> qa.getCitationChunk(1L, citationId));
        assertRows(1, 1, 1);
    }

    @Test
    void rebuildWaitsForPublicationThenHistoryShowsAStaleVersion() throws Exception {
        CountDownLatch persistenceLocked = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        QaService gated = service(gatedMessages(persistenceLocked, releasePersistence), citations);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publishing = executor.submit(() -> persist(gated, answer()));
            assertTrue(persistenceLocked.await(5, TimeUnit.SECONDS));
            CountDownLatch writerStarted = new CountDownLatch(1);
            var rebuilding = executor.submit(() -> transactions.executeWithoutResult(status -> {
                writerStarted.countDown();
                jdbc.queryForList("SELECT id FROM document WHERE id=20 FOR UPDATE", Long.class);
                jdbc.update("UPDATE document SET generation=2,parse_status='PENDING' WHERE id=20");
            }));
            try {
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> rebuilding.get(150, TimeUnit.MILLISECONDS));
            } finally { releasePersistence.countDown(); }
            publishing.get(5, TimeUnit.SECONDS);
            rebuilding.get(5, TimeUnit.SECONDS);
        } finally { releasePersistence.countDown(); }
        assertRows(1, 1, 1);
        Long citationId = jdbc.queryForObject("SELECT id FROM qa_citation", Long.class);
        QaCitationDetailResponse detail = qa.getCitationChunk(1L, citationId);
        assertEquals("STALE_VERSION", detail.evidenceState());
        assertEquals(CONTENT, detail.content());
        assertNull(detail.charStart());
    }

    @Test
    void deletionThatOwnsTheDocumentLockPreventsPublication() throws Exception {
        CountDownLatch deletionLocked = new CountDownLatch(1);
        CountDownLatch releaseDeletion = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var deleting = executor.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM document WHERE id=20 FOR UPDATE", Long.class);
                jdbc.update("UPDATE document SET parse_status='DELETED' WHERE id=20");
                deletionLocked.countDown();
                await(releaseDeletion);
            }));
            assertTrue(deletionLocked.await(5, TimeUnit.SECONDS));
            var publishing = executor.submit(() -> persist(qa, answer()));
            try { assertThrows(TimeoutException.class, () -> publishing.get(150, TimeUnit.MILLISECONDS)); }
            finally { releaseDeletion.countDown(); }
            deleting.get(5, TimeUnit.SECONDS);
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> publishing.get(5, TimeUnit.SECONDS));
            assertInstanceOf(BusinessException.class, failure.getCause());
        } finally { releaseDeletion.countDown(); }
        assertRows(0, 0, 0);
    }

    @Test
    void pipelineCheckpointUsesCompatibleLocksAndCannotModifyEvidenceBeforeCommit() throws Exception {
        jdbc.update("""
                INSERT INTO parse_task(id,task_no,document_id,user_id,status,stage,generation,claim_token,lease_until,
                  retry_count,max_retry_count,next_attempt_time,create_time,update_time)
                VALUES(40,'qa-lock-it',20,1,'PROCESSING','INDEX',1,'owned-token',TIMESTAMPADD(MINUTE,2,NOW(6)),0,5,NOW(6),NOW(),NOW())
                """);
        ParseTaskEntity task = sql.getMapper(ParseTaskMapper.class).selectById(40L);
        PipelineStore pipeline = new PipelineStore(jdbc, transactions, sql.getMapper(ParseTaskMapper.class),
                sql.getMapper(DocumentMapper.class), new PipelineProperties(), mock(EmbeddingClient.class), new EmbeddingProperties());
        CountDownLatch persistenceLocked = new CountDownLatch(1);
        CountDownLatch releasePersistence = new CountDownLatch(1);
        QaService gated = service(gatedMessages(persistenceLocked, releasePersistence), citations);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var publishing = executor.submit(() -> persist(gated, answer()));
            assertTrue(persistenceLocked.await(5, TimeUnit.SECONDS));
            var checkpoint = executor.submit(() -> pipeline.checkpoint(task,
                    doc -> jdbc.update("UPDATE document_chunk SET content='replacement content' WHERE id=30")));
            try { assertThrows(TimeoutException.class, () -> checkpoint.get(150, TimeUnit.MILLISECONDS)); }
            finally { releasePersistence.countDown(); }
            publishing.get(5, TimeUnit.SECONDS);
            assertTrue(checkpoint.get(5, TimeUnit.SECONDS));
        } finally { releasePersistence.countDown(); }
        assertRows(1, 1, 1);
        assertEquals("SUCCESS", jdbc.queryForObject("SELECT status FROM parse_task WHERE id=40", String.class));
        assertEquals("STALE_VERSION", qa.getCitationChunk(1L, jdbc.queryForObject("SELECT id FROM qa_citation", Long.class)).evidenceState());
    }

    private QaMessageMapper gatedMessages(CountDownLatch locked, CountDownLatch release) {
        QaMessageMapper gated = mock(QaMessageMapper.class, delegatesTo(messages));
        doAnswer(invocation -> {
            locked.countDown();
            await(release);
            return messages.insert((QaMessageEntity) invocation.getArgument(0));
        }).when(gated).insert(any(QaMessageEntity.class));
        return gated;
    }

    private QaService service(QaMessageMapper messageMapper, QaCitationMapper citationMapper) {
        return new QaService(new KnowledgeBaseService(sql.getMapper(KnowledgeBaseMapper.class)),
                sql.getMapper(QaSessionMapper.class), messageMapper, citationMapper,
                mock(RagRetriever.class), mock(RagPromptBuilder.class), mock(RagQueryRewriter.class),
                mock(RagAnswerValidator.class), new RagProperties(), mock(AiClient.class), new AiProperties(),
                sql.getMapper(DocumentChunkMapper.class), mock(EvidenceAnswerService.class), claims,
                sql.getMapper(DocumentMapper.class), jdbc, manager, Runnable::run);
    }

    private VerifiedAnswer answer() {
        RetrievedChunk chunk = new RetrievedChunk(30L, 20L, 1L, "guide.txt", 0, CONTENT, 1.0, 1.0, 1.0, "HYBRID", 1.0);
        EvidenceSpan span = new EvidenceSpan(chunk, "S1", CONTENT, 0, CONTENT.length(), EvidenceAnswerValidator.sha256(CONTENT), 1L);
        return new VerifiedAnswer(CONTENT + " [S1]", "SUPPORTED", "MODEL_VERIFIED",
                List.of(new GroundedClaim(0, CONTENT, List.of(span))), 0, true, "test-verifier", 10, 5, 20L);
    }

    private Object persist(QaService service, VerifiedAnswer answer) throws Exception {
        Method method = QaService.class.getDeclaredMethod("persistVerified", QaSessionEntity.class,
                VerifiedAnswer.class, String.class, Integer.class, Integer.class, Long.class);
        method.setAccessible(true);
        try { return method.invoke(service, session, answer, "test-generator", 20, 10, 30L); }
        catch (InvocationTargetException wrapper) {
            if (wrapper.getCause() instanceof Exception error) throw error;
            throw wrapper;
        }
    }

    private void assertRows(int messageCount, int claimCount, int citationCount) {
        assertEquals(messageCount, jdbc.queryForObject("SELECT COUNT(*) FROM qa_message", Integer.class));
        assertEquals(claimCount, jdbc.queryForObject("SELECT COUNT(*) FROM qa_claim", Integer.class));
        assertEquals(citationCount, jdbc.queryForObject("SELECT COUNT(*) FROM qa_citation", Integer.class));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "test gate did not open"); }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IllegalStateException(error); }
    }
}
