package com.studyflow.agent;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.agent.tools.KnowledgeTools;
import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.rag.RagRetriever;
import com.studyflow.workspace.WorkspaceService;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

class KnowledgeToolsTest {
    final AgentJson json = new AgentJson(new ObjectMapper());
    final KnowledgeTools tools =
            new KnowledgeTools(
                    mock(KnowledgeBaseService.class),
                    mock(WorkspaceService.class),
                    mock(RagRetriever.class),
                    mock(JdbcTemplate.class),
                    json,
                    new AgentProperties(),
                    mock(com.studyflow.notebook.NoteService.class));

    @Test
    void schemaBlocksScopeInjectionUnknownToolsAndInvalidArguments() {
        for (String args :
                List.of(
                        "{\"query\":\"redis\",\"userId\":2}",
                        "{\"query\":\"redis\",\"topK\":999}",
                        "{\"query\":\" \"}",
                        "{\"query\":123}",
                        "{}"))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> tools.validate("search_knowledge", json.tree(args)));
        assertThrows(IllegalArgumentException.class, () -> tools.validate("exec", json.tree("{}")));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        tools.validate(
                                "read_document",
                                json.tree("{\"documentId\":\"9999999999999999999\"}")));
        assertDoesNotThrow(() ->
                tools.validate(
                        "read_document",
                        json.tree("{\"documentId\":\"123\",\"afterChunkIndex\":2}")));
    }

    @Test
    void allToolsHaveActualJsonSchemasAndNoWriteCapabilities() {
        assertEquals(8, tools.definitions().size());
        for (var tool : tools.definitions()) {
            assertEquals("object", tool.parameters().path("type").asText());
            assertFalse(tool.parameters().path("additionalProperties").asBoolean());
        }
    }

    @Test
    void duplicateSignaturesAreIndependentOfPropertyOrder() {
        assertEquals(
                json.canonical(json.tree("{\"query\":\"redis\",\"topK\":5}")),
                json.canonical(json.tree("{\"topK\":5,\"query\":\"redis\"}")));
    }
}
