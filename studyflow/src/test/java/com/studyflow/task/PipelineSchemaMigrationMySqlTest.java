package com.studyflow.task;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "pipeline.mysql.tests", matches = "true")
class PipelineSchemaMigrationMySqlTest {
    @Test void dockerInitializedV7DatabaseUpgradesWithoutEditingAppliedMigrations() throws Exception {
        String url=System.getProperty("pipeline.upgrade.test.jdbc-url", "jdbc:mysql://localhost:3307/studyflow_pipeline_upgrade_test?useSSL=false&allowPublicKeyRetrieval=true");
        if (!url.matches("jdbc:mysql://[^/]+/[a-zA-Z0-9_]+_upgrade_test(?:\\?.*)?")) throw new IllegalArgumentException("Requires an isolated *_upgrade_test schema");
        var source=new DriverManagerDataSource(url,System.getProperty("pipeline.test.username","studyflow"),
                System.getenv().getOrDefault("PIPELINE_TEST_DB_PASSWORD","studyflow123"));
        // This database is explicitly reserved for this destructive migration rehearsal.
        Flyway.configure().dataSource(source).cleanDisabled(false).load().clean();
        try(var connection=source.getConnection()) {
            ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/migration/V1__core_schema.sql"));
        }
        Path historical=Files.createTempDirectory("studyflow-historical-migrations-");
        for(String name : new String[]{"V3__qa_rag_tables.sql","V4__chunk_upload_tables.sql","V5__rag_embedding_columns.sql","V6__learning_agent_tables.sql","V7__ocr_document_metadata.sql"}) {
            try(var in=new ClassPathResource("db/migration/"+name).getInputStream()) { Files.copy(in,historical.resolve(name)); }
        }
        Flyway.configure().dataSource(source).locations("filesystem:"+historical).baselineOnMigrate(true).baselineVersion("0").load().migrate();
        JdbcTemplate jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO knowledge_base(id,user_id,name,status,create_time,update_time) VALUES(99,99,'preserved','ACTIVE',NOW(),NOW())");
        Flyway.configure().dataSource(source).locations("classpath:db/migration").outOfOrder(true).load().migrate();
        assertEquals("preserved",jdbc.queryForObject("SELECT name FROM knowledge_base WHERE id=99",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='1' AND success=1",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='parse_task' AND column_name='claim_token'",Integer.class));
        for(Path file:Files.list(historical).toList()) Files.delete(file);
        Files.delete(historical);
    }
}
