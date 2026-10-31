package com.studyflow.agent.harness;

import static com.studyflow.agent.AgentTypes.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.agent.*;
import com.studyflow.agent.context.*;
import com.studyflow.agent.memory.MemorySystem;
import com.studyflow.agent.skills.SkillCatalog;
import com.studyflow.ai.AiToolRequest.Definition;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

class ContextBudgetTest {
    final AgentJson json = new AgentJson(new ObjectMapper().findAndRegisterModules());
    final TokenCounter tokens = new TokenCounter();

    Claim claim(CheckpointState state) {
        return new Claim(10L, 2L, 3L, 4L, "比较缓存一致性与原子性", "DECIDE", "TOOL", "lease", 1, state);
    }

    @Test
    void compressesToolResultsWithoutLosingGoalScopeOrStableReferences() {
        var config = new HarnessProperties();
        config.setInputTokens(1800);
        var memory = mock(MemorySystem.class);
        when(memory.retrieve(any(), anyList(), anyString(), anyInt())).thenReturn(List.of());
        var context =
                new ContextManager(
                        tokens, config, json, memory, new SkillCatalog(), mock(JdbcTemplate.class));
        var state =
                CheckpointState.initial(List.of("旧对话".repeat(300)), new AgentProperties())
                        .withScope(List.of(4L, 5L), "compare")
                        .withPlan(List.of("检索证据", "对比差异"));
        for (int i = 1; i <= 4; i++)
            state =
                    state.withTool(
                            new Observation(
                                    i,
                                    "read_note",
                                    "SUCCESS",
                                    json.tree("{\"noteId\":\"12\"}"),
                                    "🧠原文知识\\\"\n".repeat(5000),
                                    "读取来源"),
                            List.of());
        var result =
                context.assemble(
                        claim(state),
                        "tool",
                        List.of(
                                new Definition(
                                        "finish_research",
                                        "Finish",
                                        json.tree("{\"type\":\"object\"}"))));
        assertTrue(result.estimatedTokens() <= 1800);
        assertTrue(result.compressedResults() > 0);
        var payload = json.tree(result.user());
        assertEquals(claim(state).goal(), payload.path("goal").asText());
        assertEquals(2, payload.path("knowledgeBaseIds").size());
        assertEquals(4, payload.path("observations").size());
        assertEquals(
                "tool-result:10:1", payload.path("observations").get(0).path("resultRef").asText());
        assertTrue(
                context.measure(result.system(), result.user(), result.tools()).estimatedTokens()
                        <= 1800);
    }

    @Test
    void everyProviderPurposeMustRespectInputBudgetAndUnicodeBoundaries() {
        var config = new HarnessProperties();
        config.setInputTokens(1500);
        var context =
                new ContextManager(
                        tokens,
                        config,
                        json,
                        mock(MemorySystem.class),
                        new SkillCatalog(),
                        mock(JdbcTemplate.class));
        assertThrows(
                IllegalArgumentException.class,
                () -> context.measure("immutable system", "知识".repeat(10000), List.of()));
        var original = "🧠中文👩🏽‍💻Unicode".repeat(1000);
        var value = tokens.prefix(original, 70);
        assertTrue(tokens.count(value) <= 70);
        assertFalse(Character.isHighSurrogate(value.charAt(value.length() - 1)));
        assertTrue(original.startsWith(value));
    }
}
