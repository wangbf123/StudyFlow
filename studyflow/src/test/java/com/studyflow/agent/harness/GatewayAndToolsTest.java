package com.studyflow.agent.harness;

import static com.studyflow.agent.AgentTypes.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.agent.*;
import com.studyflow.agent.context.*;
import com.studyflow.agent.governance.AgentPolicy;
import com.studyflow.agent.memory.MemorySystem;
import com.studyflow.agent.model.ModelGateway;
import com.studyflow.agent.observability.HarnessTelemetry;
import com.studyflow.agent.planning.TaskManager;
import com.studyflow.agent.runtime.DockerEnvironment;
import com.studyflow.agent.skills.SkillCatalog;
import com.studyflow.agent.state.*;
import com.studyflow.agent.tools.*;
import com.studyflow.ai.*;
import com.studyflow.common.BusinessException;

import io.opentelemetry.api.trace.Span;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

class GatewayAndToolsTest {
    final AgentJson json = new AgentJson(new ObjectMapper().findAndRegisterModules());

    Claim claim() {
        return new Claim(
                10L,
                2L,
                3L,
                4L,
                "研究缓存",
                "DECIDE",
                "PLAN",
                "lease",
                1,
                CheckpointState.initial(List.of(), new AgentProperties()));
    }

    @Test
    void oversizedRequestsCannotConsumeCallsAndRepeatedFailuresOpenCircuit() {
        var ai = mock(AiClient.class);
        var config = new HarnessProperties();
        var state = mock(AgentStateStore.class);
        var telemetry = mock(HarnessTelemetry.class);
        when(telemetry.start(any(), anyString(), anyString())).thenReturn(Span.getInvalid());
        var context =
                new ContextManager(
                        new TokenCounter(),
                        config,
                        json,
                        mock(MemorySystem.class),
                        new SkillCatalog(),
                        mock(JdbcTemplate.class));
        var gateway =
                new ModelGateway(
                        ai,
                        new AiProperties(),
                        config,
                        state,
                        telemetry,
                        json,
                        new ObjectMapper(),
                        context);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        gateway.chat(
                                claim(),
                                "plan",
                                new AiChatRequest("system", "知识".repeat(12000), "q", null, 0.0)));
        verifyNoInteractions(state);
        when(ai.chat(any())).thenThrow(new BusinessException(502, "fixture provider failure"));
        for (int i = 0; i < 3; i++)
            assertThrows(
                    BusinessException.class,
                    () ->
                            gateway.chat(
                                    claim(),
                                    "plan",
                                    new AiChatRequest("system", "user", "q", null, 0.0)));
        var blocked =
                assertThrows(
                        BusinessException.class,
                        () ->
                                gateway.chat(
                                        claim(),
                                        "plan",
                                        new AiChatRequest("system", "user", "q", null, 0.0)));
        assertEquals(429, blocked.getCode());
        verify(state, times(3)).reserveModel(any(), anyInt());
        verify(state, times(3)).uncertainUsage(any());
        verify(ai, times(3)).chat(any());
    }

    @Test
    void toolRegistryRejectsUnlistedToolsAndExternalSchemaReferences() {
        var knowledge = mock(KnowledgeTools.class);
        when(knowledge.definitions())
                .thenReturn(
                        List.of(
                                new AiToolRequest.Definition(
                                        "read_note",
                                        "Read",
                                        json.tree(
                                                "{\"type\":\"object\",\"properties\":{\"noteId\":{\"type\":\"string\"}},\"required\":[\"noteId\"],\"additionalProperties\":false}"))));
        var mcp = mock(McpConnections.class);
        when(mcp.definitions(any()))
                .thenReturn(
                        Map.of(
                                "mcp__x__bad",
                                new AiToolRequest.Definition(
                                        "mcp__x__bad",
                                        "Untrusted",
                                        json.tree("{\"$ref\":\"http://127.0.0.1/never-fetch\"}"))));
        var registry =
                new ToolRegistry(
                        knowledge,
                        mcp,
                        mock(MemorySystem.class),
                        mock(ToolResultStore.class),
                        mock(DockerEnvironment.class),
                        mock(AgentPolicy.class),
                        mock(TaskManager.class),
                        new SkillCatalog(),
                        json);
        assertTrue(
                registry.catalog(claim()).stream().noneMatch(t -> t.name().equals("mcp__x__bad")));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(claim(), "read_note", json.tree("{\"noteId\":42}")));
        assertThrows(
                IllegalArgumentException.class,
                () -> registry.validate(claim(), "shell", json.tree("{}")));
        assertDoesNotThrow(
                () -> registry.validate(claim(), "read_note", json.tree("{\"noteId\":\"42\"}")));
        verify(knowledge, never()).execute(any(), any());
    }
}
