package com.studyflow.agent;

import static com.studyflow.agent.AgentTypes.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.agent.loop.AgentGraph;
import com.studyflow.agent.loop.AgentHarness;

import org.junit.jupiter.api.Test;

import java.util.*;

class AgentGraphTest {
    final AgentJson json = new AgentJson(new ObjectMapper().findAndRegisterModules());
    final AgentHarness nodes = mock(AgentHarness.class);

    Claim claim(String node, String previous) {
        return new Claim(
                1L,
                2L,
                3L,
                4L,
                "研究 Redis",
                node,
                previous,
                "lease",
                1,
                CheckpointState.initial(List.of(), new AgentProperties()));
    }

    @Test
    void graphExecutesOnePlanNodeAndStopsAtCheckpoint() throws Exception {
        var run = claim("PLAN", "__START__");
        when(nodes.execute("PLAN", run))
                .thenReturn(
                        new NodeResult(
                                run.state().withPlan(List.of("检索资料")),
                                "DECIDE",
                                "plan_created",
                                Map.of(),
                                null));
        NodeResult result = new AgentGraph(nodes, json).step(run);
        assertEquals("DECIDE", result.nextNode());
        assertEquals(List.of("检索资料"), result.state().plan());
        verify(nodes, times(1)).execute(anyString(), any());
    }

    @Test
    void freshGraphResumesToolWithoutRepeatingPlanner() throws Exception {
        var run = claim("TOOL", "DECIDE");
        when(nodes.execute("TOOL", run))
                .thenReturn(
                        new NodeResult(run.state(), "DECIDE", "tool_completed", Map.of(), null));
        assertEquals("DECIDE", new AgentGraph(nodes, json).step(run).nextNode());
        verify(nodes).execute("TOOL", run);
        verifyNoMoreInteractions(nodes);
    }

    @Test
    void conditionalDecisionCanFinishResearch() throws Exception {
        var run = claim("DECIDE", "TOOL");
        when(nodes.execute("DECIDE", run))
                .thenReturn(
                        new NodeResult(
                                run.state(), "SYNTHESIZE", "research_completed", Map.of(), null));
        assertEquals("SYNTHESIZE", new AgentGraph(nodes, json).step(run).nextNode());
        verifyNoMoreInteractionsAfterOneCall();
    }

    @Test
    void teamWaitIsOneDurableNodeAndCanResumeUntilChildrenFinish() throws Exception {
        var run = claim("WAIT_CHILDREN", "WAIT_CHILDREN");
        when(nodes.execute("WAIT_CHILDREN", run))
                .thenReturn(
                        new NodeResult(
                                run.state(), "WAIT_CHILDREN", "team_waiting", Map.of(), null));
        assertEquals("WAIT_CHILDREN", new AgentGraph(nodes, json).step(run).nextNode());
        verify(nodes, times(1)).execute("WAIT_CHILDREN", run);
    }

    @Test
    void rejectedDecisionYieldsInsteadOfSpinningInsideOneWorker() throws Exception {
        var run = claim("DECIDE", "DECIDE");
        when(nodes.execute("DECIDE", run))
                .thenReturn(new NodeResult(run.state(), "DECIDE", "tool_rejected", Map.of(), null));
        assertEquals("DECIDE", new AgentGraph(nodes, json).step(run).nextNode());
        verify(nodes, times(1)).execute("DECIDE", run);
    }

    private void verifyNoMoreInteractionsAfterOneCall() {
        verify(nodes, times(1)).execute(anyString(), any());
    }

    @Test
    void planParserRejectsDuplicateKeysTrailingDataAndUnboundedSteps() {
        for (String raw :
                List.of(
                        "{\"steps\":[\"a\"],\"steps\":[\"b\"]}",
                        "{\"steps\":[\"a\"]} {}",
                        "{\"steps\":[]}",
                        "{\"steps\":[\"a\",\"b\",\"c\",\"d\",\"e\",\"f\"]}"))
            assertThrows(IllegalArgumentException.class, () -> json.plan(raw));
        assertEquals(List.of("检索"), json.plan("{\"steps\":[\"检索\"]}"));
    }
}
