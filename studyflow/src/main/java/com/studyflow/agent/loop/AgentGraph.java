package com.studyflow.agent.loop;

import static com.studyflow.agent.AgentTypes.*;

import static org.bsc.langgraph4j.StateGraph.*;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

import com.studyflow.agent.*;

import org.bsc.langgraph4j.*;
import org.bsc.langgraph4j.checkpoint.Checkpoint;
import org.bsc.langgraph4j.state.AgentState;
import org.springframework.stereotype.Component;

import java.util.*;

/** Full graph topology; one node per durable dispatch. Resume is the framework's checkpoint API. */
@Component
public class AgentGraph {
    private final CompiledGraph<AgentState> graph;
    private final AgentJson json;

    public AgentGraph(AgentHarness nodes, AgentJson json) throws GraphStateException {
        this.json = json;
        var definition = new StateGraph<>(AgentState::new);
        for (String node :
                List.of(
                        "PLAN",
                        "TEAM_DISPATCH",
                        "WAIT_CHILDREN",
                        "DECIDE",
                        "TOOL",
                        "SYNTHESIZE",
                        "VERIFY")) {
            definition.addNode(
                    node,
                    node_async(state -> {
                        Claim claim =
                                json.read(state.<String>value("claim").orElseThrow(), Claim.class);
                        NodeResult result = nodes.execute(node, claim);
                        return Map.of("result", json.write(result), "next", result.nextNode());
                    }));
        }
        // A persisted self-transition yields this invocation to END. interruptAfter alone
        // does not suspend a LangGraph4j self-loop, so it must not spin inside a worker.
        definition
                .addEdge(START, "PLAN")
                .addConditionalEdges(
                        "PLAN",
                        edge_async(state -> state.<String>value("next").orElseThrow()),
                        Map.of("DECIDE", "DECIDE", "TEAM_DISPATCH", "TEAM_DISPATCH"))
                .addEdge("TEAM_DISPATCH", "WAIT_CHILDREN")
                .addConditionalEdges(
                        "WAIT_CHILDREN",
                        edge_async(state -> state.<String>value("next").orElseThrow()),
                        Map.of("WAIT_CHILDREN", END, "DECIDE", "DECIDE"))
                .addConditionalEdges(
                        "DECIDE",
                        edge_async(state -> state.<String>value("next").orElseThrow()),
                        Map.of("TOOL", "TOOL", "DECIDE", END, "SYNTHESIZE", "SYNTHESIZE"))
                .addEdge("TOOL", "DECIDE")
                .addEdge("SYNTHESIZE", "VERIFY")
                .addEdge("VERIFY", END);
        graph =
                definition.compile(
                        CompileConfig.builder()
                                .graphId("studyflow-agent-v2")
                                .interruptAfter(
                                        "PLAN",
                                        "TEAM_DISPATCH",
                                        "WAIT_CHILDREN",
                                        "DECIDE",
                                        "TOOL",
                                        "SYNTHESIZE",
                                        "VERIFY")
                                .releaseThread(false)
                                .recursionLimit(8)
                                .build());
    }

    public NodeResult step(Claim claim) {
        Map<String, Object> data = Map.of("claim", json.write(claim));
        // No separate framework saver: the domain transaction is the sole durable authority.
        Checkpoint checkpoint =
                new Checkpoint(
                        claim.id() + ":" + claim.token(), data, claim.lastNode(), claim.stage());
        var output =
                graph.invoke(
                                GraphInput.resume(checkpoint),
                                RunnableConfig.builder().threadId(claim.id().toString()).build())
                        .orElseThrow();
        return json.read(output.<String>value("result").orElseThrow(), NodeResult.class);
    }
}
