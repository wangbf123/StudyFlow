package com.studyflow.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.common.BusinessException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentDecisionParserTest {

    private final AgentDecisionParser parser = new AgentDecisionParser(new ObjectMapper());

    @Test
    void parsesToolDecisionInsideMarkdownFence() {
        AgentDecision decision = parser.parse("""
                ```json
                {"action":"tool","tool":"search_knowledge","arguments":{"query":"Redis"},"summary":"search"}
                ```
                """);

        assertEquals("tool", decision.action());
        assertEquals("search_knowledge", decision.tool());
        assertEquals("Redis", decision.arguments().path("query").asText());
    }

    @Test
    void parsesFinalDecision() {
        AgentDecision decision = parser.parse("""
                {"action":"final","finalAnswer":"Grounded answer [C123]","summary":"done"}
                """);

        assertTrue(decision.isFinal());
        assertEquals("Grounded answer [C123]", decision.finalAnswer());
    }

    @Test
    void rejectsUnsupportedAction() {
        assertThrows(BusinessException.class,
                () -> parser.parse("{\"action\":\"delete_everything\"}"));
    }
}
