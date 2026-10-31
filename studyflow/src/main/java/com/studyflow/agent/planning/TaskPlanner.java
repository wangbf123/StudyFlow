package com.studyflow.agent.planning;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.*;
import com.studyflow.agent.context.ContextManager;
import com.studyflow.agent.model.ModelGateway;
import com.studyflow.ai.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class TaskPlanner {
    private final ModelGateway gateway;
    private final ContextManager contexts;
    private final AgentJson json;

    public List<String> plan(Claim run) {
        var context = contexts.assemble(run, "plan", List.of());
        String system =
                "You are the StudyFlow task planner. Return exactly {\"steps\":[\"short actionable"
                        + " title\"]}, 2-5 steps, each <=160 characters. "
                        + context.system();
        var response =
                gateway.chat(
                        run,
                        "plan",
                        new AiChatRequest(system, context.user(), run.goal(), null, 0.1));
        return json.plan(response.content());
    }
}
