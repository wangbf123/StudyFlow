package com.studyflow.notebook;

import static com.studyflow.agent.AgentTypes.*;
import static com.studyflow.notebook.NoteTypes.*;

import static org.junit.jupiter.api.Assertions.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.toolkit.GlobalConfigUtils;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.agent.*;
import com.studyflow.agent.state.AgentStateStore;
import com.studyflow.agent.verification.AgentEvidenceAccess;
import com.studyflow.common.BusinessException;
import com.studyflow.config.MybatisPlusMetaHandler;
import com.studyflow.kb.*;
import com.studyflow.rag.*;
import com.zaxxer.hikari.*;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/** Real locks, migrations and production services, isolated throwaway schema. */
@EnabledIfSystemProperty(named = "brain.mysql.tests", matches = "true")
class PersonalKnowledgeMySqlTest {
    static String database =
            "studyflow_brain_" + UUID.randomUUID().toString().replace("-", "") + "_test";
    static HikariDataSource dataSource;
    static JdbcTemplate jdbc;
    static String password;
    static boolean created;
    static String admin =
            "jdbc:mysql://127.0.0.1:3307/?serverTimezone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true";
    static KnowledgeBaseMapper mapper;
    static TransactionTemplate tx;
    KnowledgeBaseService bases;
    NoteService notes;
    AgentStateStore agents;
    AgentProperties properties;
    AgentJson json;

    @BeforeAll
    static void setup() throws Exception {
        password = System.getenv("EVIDENCE_TEST_DB_PASSWORD");
        assertNotNull(password, "Set EVIDENCE_TEST_DB_PASSWORD securely");
        try (var c = DriverManager.getConnection(admin, "root", password)) {
            c.createStatement().execute("CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4");
            created = true;
        }
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(admin.replace("/?", "/" + database + "?"));
        config.setUsername("root");
        config.setPassword(password);
        config.setMaximumPoolSize(12);
        config.setConnectionInitSql("SET time_zone='+00:00'");
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        var global = GlobalConfigUtils.defaults();
        global.setMetaObjectHandler(new MybatisPlusMetaHandler());
        GlobalConfigUtils.setGlobalConfig(configuration, global);
        configuration.addMapper(KnowledgeBaseMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setGlobalConfig(global);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(KnowledgeBaseMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    static void drop() throws Exception {
        if (dataSource != null) dataSource.close();
        if (created)
            try (var c = DriverManager.getConnection(admin, "root", password)) {
                c.createStatement().execute("DROP DATABASE `" + database + "`");
            }
    }

    @BeforeEach
    void seed() {
        for (String table :
                List.of(
                        "agent_memory",
                        "agent_tool_result",
                        "agent_context_manifest",
                        "agent_model_invocation",
                        "agent_plan_step",
                        "agent_connector_binding",
                        "agent_artifact",
                        "agent_event",
                        "agent_message",
                        "agent_run",
                        "agent_session",
                        "knowledge_graph_outbox",
                        "knowledge_note_link",
                        "knowledge_note_revision",
                        "knowledge_note",
                        "knowledge_base",
                        "sys_user")) jdbc.update("DELETE FROM " + table);
        for (long user : List.of(1L, 2L))
            jdbc.update(
                    "INSERT INTO"
                        + " sys_user(id,username,password_hash,nickname,role,status,create_time,update_time)"
                        + " VALUES (?,?,'test','test','USER','ACTIVE',NOW(),NOW())",
                    user,
                    "user" + user);
        for (long id : List.of(1L, 2L))
            jdbc.update(
                    "INSERT INTO"
                        + " knowledge_base(id,user_id,name,status,template_key,color,create_time,update_time)"
                        + " VALUES (?,1,?,'ACTIVE','blank','#a78bfa',NOW(),NOW())",
                    id,
                    "kb" + id);
        jdbc.update(
                "INSERT INTO knowledge_base(id,user_id,name,status,create_time,update_time) VALUES"
                        + " (3,2,'foreign','ACTIVE',NOW(),NOW())");
        json = new AgentJson(new ObjectMapper().findAndRegisterModules());
        bases = new KnowledgeBaseService(mapper, jdbc);
        notes = new NoteService(jdbc, bases, new WikiLinks(), json, Optional.empty());
        properties = new AgentProperties();
        agents = newStore();
    }

    AgentStateStore newStore() {
        return new AgentStateStore(
                jdbc,
                tx,
                bases,
                json,
                properties,
                new AgentEvidenceAccess(jdbc),
                new com.studyflow.agent.memory.MemorySystem(
                        jdbc, json, new com.studyflow.agent.governance.AgentPolicy(bases)),
                new com.studyflow.agent.state.ToolResultStore(
                        jdbc,
                        new com.studyflow.agent.context.TokenCounter(),
                        new com.studyflow.agent.HarnessProperties()),
                new com.studyflow.agent.planning.TaskManager(jdbc, json));
    }

    com.studyflow.agent.memory.MemorySystem memories() {
        return new com.studyflow.agent.memory.MemorySystem(
                jdbc, json, new com.studyflow.agent.governance.AgentPolicy(bases));
    }

    Write write(Long kb, String title, String body, int revision) {
        return new Write(kb, title, body, revision, List.of("test"), json.tree("{}"), "NOTE");
    }

    Note save(Long id, Write value) {
        return tx.execute(s -> notes.save(1L, id, value));
    }

    Note note(Long kb, String title, String body) {
        return save(null, write(kb, title, body, 0));
    }

    Run run(String key) {
        return agents.create(
                1L,
                new CreateRequest(1L, null, "Research Redis", key, List.of(1L, 2L), "research"));
    }

    NodeResult next(Claim c, String stage) {
        return new NodeResult(c.state(), stage, "test_checkpoint", Map.of(), null);
    }

    @Test
    void titleLinksResolveLaterAndSurviveRenameAndSourceEdits() {
        Note a = note(1L, "A", "[[B]] and [[B#定义|别名]]");
        assertNull(a.links().getFirst().targetId());
        Note b = note(1L, "B", "原文证据：Redis 使用原子 Lua 脚本。");
        assertEquals(b.id(), notes.get(1L, a.id()).links().getFirst().targetId());
        save(b.id(), write(1L, "B renamed", b.body(), 1));
        Note updated = save(a.id(), write(1L, "A", a.body() + "\nupdated", 1));
        assertEquals(b.id(), updated.links().getFirst().targetId());
        assertEquals("B renamed", updated.links().getFirst().currentTitle());
        assertEquals(1, notes.backlinks(1L, b.id(), List.of(1L)).size());
        assertEquals("A", notes.revision(1L, a.id(), 1).title());
    }

    @Test
    void crossLibraryLinksRequireExplicitAddressAndSelectedGraphScope() {
        Note b = note(2L, "共同主题", "属于第二个知识库");
        Note a = note(1L, "跨库", "[[共同主题]] [[note:" + b.id() + "|通用方法]]");
        assertNull(a.links().get(0).targetId());
        assertEquals(b.id(), a.links().get(1).targetId());
        assertEquals(0, notes.graph(1L, List.of(1L), null).edges().size());
        assertEquals(1, notes.graph(1L, List.of(1L, 2L), null).edges().size());
        assertThrows(BusinessException.class, () -> notes.graph(1L, List.of(3L), null));
        assertThrows(BusinessException.class, () -> notes.get(2L, a.id()));
    }

    @Test
    void deletedTargetsDisappearWithoutLeakingStaleGraphNodes() {
        Note b = note(2L, "B", "evidence");
        Note a = note(1L, "A", "[[note:" + b.id() + "|B]]");
        tx.executeWithoutResult(s -> notes.delete(1L, b.id(), 1));
        assertNull(notes.get(1L, a.id()).links().getFirst().targetId());
        assertEquals(1, notes.graph(1L, List.of(1L, 2L), a.id()).nodes().size());
    }

    @Test
    void optimisticRevisionAllowsExactlyOneConcurrentWriter() throws Exception {
        Note n = note(1L, "并发编辑", "original");
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int number = i;
                calls.add(() -> {
                    try {
                        save(n.id(), write(1L, "并发编辑", "edit" + number, 1));
                        return true;
                    } catch (BusinessException ex) {
                        assertEquals(409, ex.getCode());
                        return false;
                    }
                });
            }
            long success = 0;
            for (var f : pool.invokeAll(calls)) if (f.get()) success++;
            assertEquals(1, success);
        }
        assertEquals(2, notes.get(1L, n.id()).revision());
        assertEquals(2, notes.revisions(1L, n.id()).size());
    }

    @Test
    void duplicateNamesRollbackBodyLinksAndRevision() {
        Note a = note(1L, "A", "body"), b = note(1L, "B", "body");
        assertThrows(
                BusinessException.class,
                () -> save(a.id(), write(1L, "B", "[[note:" + b.id() + "]]", 1)));
        assertEquals("body", notes.get(1L, a.id()).body());
        assertEquals(1, notes.revisions(1L, a.id()).size());
    }

    @Test
    void libraryHierarchyRejectsCyclesAndSupportsMovingToRoot() {
        tx.executeWithoutResult(s ->
                bases.update(
                        1L,
                        2L,
                        new KnowledgeBaseUpdateRequest("child", "", 1L, "blank", "#aaaaaa")));
        assertThrows(
                BusinessException.class,
                () ->
                        tx.execute(s ->
                                bases.update(
                                        1L,
                                        1L,
                                        new KnowledgeBaseUpdateRequest(
                                                "root", "", 2L, null, null))));
        assertThrows(
                BusinessException.class, () -> tx.executeWithoutResult(s -> bases.delete(1L, 1L)));
        tx.executeWithoutResult(s ->
                bases.update(
                        1L, 2L, new KnowledgeBaseUpdateRequest("child", "", null, null, null)));
        assertNull(bases.get(1L, 2L).parentId());
        assertThrows(
                BusinessException.class,
                () ->
                        tx.execute(s ->
                                bases.create(
                                        1L,
                                        new KnowledgeBaseCreateRequest(
                                                "bad", "", 3L, null, null))));
    }

    @Test
    void newLibraryCannotExceedTwelveLevels() {
        Long parent = 1L;
        for (int depth = 2; depth <= 12; depth++) {
            Long nextParent = parent;
            parent =
                    tx.execute(s ->
                                    bases.create(
                                            1L,
                                            new KnowledgeBaseCreateRequest(
                                                    "level", "", nextParent, null, null)))
                            .id();
        }
        Long last = parent;
        assertThrows(
                BusinessException.class,
                () ->
                        tx.execute(s ->
                                bases.create(
                                        1L,
                                        new KnowledgeBaseCreateRequest(
                                                "too deep", "", last, null, null))));
    }

    @Test
    void sameRequestKeyIsIdempotentUnderConcurrencyAndConflictingScopeRejected() throws Exception {
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Callable<Long>> jobs = new ArrayList<>();
            for (int i = 0; i < 6; i++) jobs.add(() -> run("same-request-key").id());
            Set<Long> ids = new HashSet<>();
            for (var f : pool.invokeAll(jobs)) ids.add(f.get());
            assertEquals(1, ids.size());
        }
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_run", Integer.class));
        assertThrows(
                BusinessException.class,
                () ->
                        agents.create(
                                1L,
                                new CreateRequest(
                                        1L,
                                        null,
                                        "Research Redis",
                                        "same-request-key",
                                        List.of(1L),
                                        "research")));
    }

    @Test
    void expiredWorkerCannotCommitAndFreshStoreResumesSavedNode() {
        Run run = run("lease-request");
        Claim old = agents.claim(run.id());
        assertNotNull(old);
        assertNull(agents.claim(run.id()));
        jdbc.update(
                "UPDATE agent_run SET lease_until=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?",
                run.id());
        Claim current = agents.claim(run.id());
        assertNotEquals(old.token(), current.token());
        assertFalse(agents.checkpoint(old, next(old, "DECIDE"), 1));
        assertTrue(agents.checkpoint(current, next(current, "DECIDE"), 1));
        var freshStore = newStore();
        Claim resumed = freshStore.claim(run.id());
        assertEquals("DECIDE", resumed.stage());
        assertEquals("PLAN", resumed.lastNode());
        assertTrue(freshStore.get(1L, run.id()).usageIncomplete());
    }

    @Test
    void cancelFencesLateResultAndRetryKeepsOriginalTrace() {
        Run run = run("cancel-request");
        Claim claim = agents.claim(run.id());
        agents.cancel(1L, run.id());
        assertFalse(agents.checkpoint(claim, next(claim, "DECIDE"), 1));
        assertThrows(AgentStateStore.LeaseLost.class, () -> agents.reserveModel(claim));
        Run retry = agents.retry(1L, run.id(), new RetryRequest("retry-request"));
        assertEquals(run.id(), retry.retryOf());
        assertEquals(List.of(1L, 2L), retry.knowledgeBaseIds());
        assertEquals("CANCELLED", agents.get(1L, run.id()).status());
    }

    @Test
    void deadlineAndModelCallBudgetStopExecution() {
        Run run = run("deadline-request");
        jdbc.update(
                "UPDATE agent_run SET deadline_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE id=?",
                run.id());
        assertNull(agents.claim(run.id()));
        assertEquals("TIMED_OUT", agents.get(1L, run.id()).status());
        properties.setMaxModelCalls(4);
        run = run("budget-request");
        Claim claim = agents.claim(run.id());
        for (int i = 0; i < 4; i++) agents.reserveModel(claim);
        assertThrows(AgentStateStore.LeaseLost.class, () -> agents.reserveModel(claim));
        assertEquals("BUDGET_EXCEEDED", agents.get(1L, run.id()).status());
    }

    @Test
    void artifactAndEventRollBackTogetherWhenPublicationFails() {
        Run run = run("rollback-request");
        jdbc.update(
                "UPDATE agent_run SET stage='VERIFY',last_node='SYNTHESIZE' WHERE id=?", run.id());
        Claim claim = agents.claim(run.id());
        int before = agents.get(1L, run.id()).eventSeq();
        jdbc.update(
                "INSERT INTO"
                    + " agent_artifact(run_id,title,markdown,verification_status,evidence_json,create_time)"
                    + " VALUES (?,'conflict','','TEST','{}',NOW())",
                run.id());
        var answer =
                new VerifiedAnswer(
                        "no evidence",
                        "ABSTAINED",
                        "EXTRACTIVE_ONLY",
                        List.of(),
                        0,
                        true,
                        null,
                        0,
                        0,
                        0);
        assertThrows(
                Exception.class,
                () ->
                        agents.checkpoint(
                                claim,
                                new NodeResult(
                                        claim.state(),
                                        "__END__",
                                        "evidence_checked",
                                        Map.of(),
                                        answer),
                                1));
        assertEquals(before, agents.get(1L, run.id()).eventSeq());
        assertEquals("RUNNING", agents.get(1L, run.id()).status());
    }

    @Test
    void foreignAccountCannotReadCancelReplayOrRetryRun() {
        Run run = run("owner-request");
        assertThrows(BusinessException.class, () -> agents.get(2L, run.id()));
        assertThrows(BusinessException.class, () -> agents.events(2L, run.id(), 0));
        assertThrows(BusinessException.class, () -> agents.cancel(2L, run.id()));
        assertThrows(
                BusinessException.class,
                () -> agents.retry(2L, run.id(), new RetryRequest("foreign-key")));
    }

    @Test
    void noteEvidenceRejectsEditedGenerationAndWrongScope() {
        Note n = note(2L, "证据", "Redis 使用原子 Lua 脚本执行令牌桶判断。");
        var chunk =
                new RetrievedChunk(
                        n.id(), n.id(), 2L, n.title(), 0, n.body(), null, null, null, "NOTE", null);
        Source source = new Source(chunk, 1L, EvidenceAnswerValidator.sha256(n.body()));
        var access = new AgentEvidenceAccess(jdbc);
        assertTrue(access.current(1L, 2L, source, false));
        assertFalse(access.current(1L, 1L, source, false));
        save(n.id(), write(2L, n.title(), n.body() + " changed", 1));
        assertFalse(access.current(1L, 2L, source, false));
    }

    @Test
    void movingNotePreservesStableReferencesAndRejectsForeignDestination() {
        Note target = note(1L, "移动目标", "body");
        Note source = note(1L, "引用笔记", "[[移动目标]]");
        Note moved = tx.execute(s -> notes.move(1L, target.id(), 2L, 1));
        assertEquals(2, moved.revision());
        assertEquals(target.id(), notes.get(1L, source.id()).links().getFirst().targetId());
        assertEquals(0, notes.graph(1L, List.of(1L), null).edges().size());
        assertEquals(1, notes.graph(1L, List.of(1L, 2L), null).edges().size());
        assertThrows(
                BusinessException.class, () -> tx.execute(s -> notes.move(1L, target.id(), 3L, 2)));
    }

    @Test
    void distinctReportsWithSameTitleReceiveReadableUniqueNames() {
        var a = tx.execute(s -> notes.fromArtifact(1L, 1L, "报告", "one", 100L));
        var b = tx.execute(s -> notes.fromArtifact(1L, 1L, "报告", "two", 101L));
        assertNotEquals(a.id(), b.id());
        assertEquals("报告 · 2", b.title());
    }

    @Test
    void successfulArtifactCreatesOneLinkedNoteWhenSavedTwice() {
        Note n = note(1L, "原文", "原文内容");
        Note a =
                tx.execute(s ->
                        notes.fromArtifact(1L, 1L, "报告", "引用 [[note:" + n.id() + "|原文]]", 100L));
        Note b = tx.execute(s -> notes.fromArtifact(1L, 1L, "报告", "ignored", 100L));
        assertEquals(a.id(), b.id());
        assertEquals(n.id(), a.links().getFirst().targetId());
    }

    @Test
    void sourceRevisionAndLibraryScopeControlMemoryRecall() {
        var source = note(1L, "概念", "Redis Lua 原子性证据");
        var memory = memories();
        var item =
                memory.put(
                        1L,
                        new com.studyflow.agent.memory.MemorySystem.Write(
                                1L, "SEMANTIC", "Lua 原子性", "先阅读原子脚本的出处", source.id(), 30));
        assertEquals(1, memory.retrieve(1L, List.of(1L), "Lua", 5).size());
        assertTrue(memory.retrieve(1L, List.of(2L), "Lua", 5).isEmpty());
        assertTrue(memory.retrieve(2L, List.of(1L), "Lua", 5).isEmpty());
        save(source.id(), write(1L, source.title(), "更新后的事实内容", 1));
        assertTrue(memory.retrieve(1L, List.of(1L), "Lua", 5).isEmpty());
        assertFalse(memory.manage(1L, List.of(1L)).getFirst().current());
        memory.forget(1L, item.id());
        assertTrue(memory.manage(1L, List.of(1L)).isEmpty());
    }

    @Test
    void executionMemoryCannotBridgeUnselectedDomains() {
        var task = run("memory-scope-request");
        var claim = agents.claim(task.id());
        memories().rememberRun(claim, "SUCCESS");
        assertTrue(memories().retrieve(1L, List.of(1L), "Redis", 5).isEmpty());
        assertEquals(1, memories().retrieve(1L, List.of(1L, 2L), "Redis", 5).size());
        assertThrows(
                BusinessException.class,
                () ->
                        memories()
                                .put(
                                        1L,
                                        new com.studyflow.agent.memory.MemorySystem.Write(
                                                1L, "SEMANTIC", "无出处", "不能保存无出处的事实", null, 30)));
    }

    @Test
    void childrenAreScopedIdempotentAndCancellationFencesLateResults() {
        var root =
                agents.create(
                        1L,
                        new CreateRequest(
                                1L,
                                null,
                                "研究 Redis",
                                "team-request",
                                List.of(1L, 2L),
                                "research",
                                null,
                                true));
        var parent = agents.claim(root.id());
        var child = agents.createChild(parent, 2L, 0);
        assertEquals(child.id(), agents.createChild(parent, 2L, 0).id());
        assertEquals(List.of(2L), child.knowledgeBaseIds());
        assertEquals(root.id(), child.rootRunId());
        assertEquals(1, child.depth());
        assertThrows(BusinessException.class, () -> agents.createChild(parent, 3L, 1));
        var active = agents.claim(child.id());
        assertThrows(BusinessException.class, () -> agents.createChild(active, 2L, 0));
        agents.reserveModel(active, 200);
        agents.cancel(1L, root.id());
        assertTrue(agents.get(1L, root.id()).usageIncomplete());
        assertEquals(
                200,
                jdbc.queryForObject(
                        "SELECT root_token_charge FROM agent_run WHERE id=?",
                        Long.class,
                        root.id()));
        assertEquals("CANCELLED", agents.get(1L, child.id()).status());
        assertFalse(agents.checkpoint(active, next(active, "DECIDE"), 1));
        assertThrows(AgentStateStore.LeaseLost.class, () -> agents.reserveModel(active, 100));
        var retry = agents.retry(1L, root.id(), new RetryRequest("retry-team-request"));
        assertTrue(Boolean.TRUE.equals(agents.claim(retry.id()).state().collaboration()));
    }

    @Test
    void parallelChildrenCannotOverspendRootTokenBudget() throws Exception {
        var root =
                agents.create(
                        1L,
                        new CreateRequest(
                                1L,
                                null,
                                "研究 Redis",
                                "shared-budget-request",
                                List.of(1L, 2L),
                                "research",
                                null,
                                true));
        var parent = agents.claim(root.id());
        var one = agents.claim(agents.createChild(parent, 1L, 0).id());
        var two = agents.claim(agents.createChild(parent, 2L, 1).id());
        jdbc.update(
                "UPDATE agent_run SET"
                        + " state_json=JSON_SET(state_json,'$.budget.maxReportedTokens',1000) WHERE"
                        + " id=?",
                root.id());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var calls =
                    List.<Callable<Boolean>>of(
                            () -> {
                                try {
                                    agents.reserveModel(one, 700);
                                    return true;
                                } catch (AgentStateStore.LeaseLost ex) {
                                    return false;
                                }
                            },
                            () -> {
                                try {
                                    agents.reserveModel(two, 700);
                                    return true;
                                } catch (AgentStateStore.LeaseLost ex) {
                                    return false;
                                }
                            });
            int accepted = 0;
            for (var result : pool.invokeAll(calls)) if (result.get()) accepted++;
            assertEquals(1, accepted);
        }
        assertEquals(
                700,
                jdbc.queryForObject(
                        "SELECT root_reserved_tokens FROM agent_run WHERE id=?",
                        Long.class,
                        root.id()));
        Claim winner = "RUNNING".equals(agents.get(1L, one.id()).status()) ? one : two;
        agents.uncertainUsage(winner);
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT root_reserved_tokens FROM agent_run WHERE id=?",
                        Long.class,
                        root.id()));
        assertEquals(
                700,
                jdbc.queryForObject(
                        "SELECT root_token_charge FROM agent_run WHERE id=?",
                        Long.class,
                        root.id()));
    }

    @Test
    void toolArtifactsAndPlanChangesOnlyCommitWithLiveCheckpoint() {
        var task = run("artifact-lease-request");
        var active = agents.claim(task.id());
        agents.checkpoint(
                active,
                new NodeResult(
                        active.state().withPlan(List.of("检索资料")),
                        "DECIDE",
                        "plan_created",
                        Map.of(),
                        null),
                1);
        var decide = agents.claim(task.id());
        agents.checkpoint(
                decide,
                new NodeResult(
                        decide.state()
                                .withDecision(
                                        new PendingCall("knowledge_overview", json.tree("{}"))),
                        "TOOL",
                        "tool_selected",
                        Map.of(),
                        null),
                1);
        var tool = agents.claim(task.id());
        var observation =
                new Observation(
                        1,
                        "knowledge_overview",
                        "SUCCESS",
                        json.tree("{}"),
                        "large-result-".repeat(2000),
                        "资料统计");
        agents.cancel(1L, task.id());
        assertFalse(
                agents.checkpoint(
                        tool,
                        new NodeResult(
                                tool.state().withTool(observation, List.of()),
                                "DECIDE",
                                "tool_completed",
                                Map.of(),
                                null),
                        1));
        assertEquals(
                0,
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM agent_tool_result WHERE run_id=?",
                        Integer.class,
                        task.id()));
    }

    @Test
    void repeatedExperiencesCannotCrowdOutPreferencesAndSourcedKnowledge() {
        var source = note(1L, "记忆来源", "任务状态与可复用知识应分别保存。");
        var memory = memories();
        var knowledge =
                memory.put(
                        1L,
                        new com.studyflow.agent.memory.MemorySystem.Write(
                                1L, "SEMANTIC", "知识提示", "任务状态与知识区分", source.id(), 30));
        var preference =
                memory.put(
                        1L,
                        new com.studyflow.agent.memory.MemorySystem.Write(
                                1L, "PREFERENCE", "输出偏好", "先给例子再解释", null, 365));
        for (long i = 0; i < 110; i++)
            memory.rememberRun(
                    new Claim(
                            1000L + i,
                            2L,
                            1L,
                            1L,
                            "根据现有笔记研究知识管理与可信来源，生成带原文引用的报告。",
                            "VERIFY",
                            "SYNTHESIZE",
                            "lease",
                            1,
                            CheckpointState.initial(List.of(), properties)
                                    .withScope(List.of(1L), "research")),
                    "SUCCESS");
        var recalled = memory.retrieve(1L, List.of(1L), "根据现有笔记研究知识管理与可信来源，生成带原文引用的报告。", 5);
        assertEquals(5, recalled.size());
        assertTrue(
                memory.manage(1L, List.of(1L)).stream()
                        .anyMatch(m -> m.id().equals(preference.id())));
        assertTrue(recalled.stream().anyMatch(m -> m.id().equals(knowledge.id())));
        assertTrue(recalled.stream().anyMatch(m -> m.id().equals(preference.id())));
    }
}
