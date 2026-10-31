package com.studyflow.agent.planning;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.*;
import com.studyflow.common.BusinessException;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class TaskManager {
    private final JdbcTemplate jdbc;
    private final AgentJson json;

    public void save(Long run, List<String> plan) {
        for (int i = 0; i < plan.size(); i++)
            jdbc.update(
                    "INSERT IGNORE INTO"
                        + " agent_plan_step(run_id,step_no,title,dependencies_json,status) VALUES"
                        + " (?,?,?,?,'PLANNED')",
                    run,
                    i + 1,
                    plan.get(i),
                    json.write(i == 0 ? List.of() : List.of(i)));
    }

    public List<Map<String, Object>> list(Long run) {
        return jdbc.query(
                "SELECT * FROM agent_plan_step WHERE run_id=? ORDER BY step_no",
                (rs, n) ->
                        Map.of(
                                "stepNo",
                                rs.getInt("step_no"),
                                "title",
                                rs.getString("title"),
                                "dependsOn",
                                json.tree(rs.getString("dependencies_json")),
                                "status",
                                rs.getString("status")),
                run);
    }

    public Object complete(Claim run, int step, int evidenceStep) {
        boolean evidence =
                run.state().observations().stream()
                        .anyMatch(o -> o.number() == evidenceStep && "SUCCESS".equals(o.status()));
        if (!evidence) throw new BusinessException(400, "完成步骤需要本次成功工具结果作为依据");
        var rows = list(run.id());
        var task =
                rows.stream()
                        .filter(r -> (int) r.get("stepNo") == step)
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(404, "计划步骤不存在"));
        for (var dep : json.tree(json.write(task.get("dependsOn"))))
            if (rows.stream()
                    .noneMatch(r ->
                            (int) r.get("stepNo") == dep.asInt()
                                    && "AGENT_COMPLETED".equals(r.get("status"))))
                throw new BusinessException(409, "前置步骤尚未完成");
        return Map.of(
                "stepNo",
                step,
                "status",
                "AGENT_COMPLETED",
                "evidenceToolStep",
                evidenceStep,
                "semanticCompletion",
                "NOT_INDEPENDENTLY_SCORED");
    }

    /** Called only inside the lease-fenced checkpoint transaction. */
    public void commitCompletion(Claim run, Observation observation) {
        if (!"complete_plan_step".equals(observation.tool())
                || !"SUCCESS".equals(observation.status())) return;
        int step = observation.arguments().path("stepNo").asInt();
        complete(run, step, observation.arguments().path("evidenceStep").asInt());
        jdbc.update(
                "UPDATE agent_plan_step SET status='AGENT_COMPLETED' WHERE run_id=? AND step_no=?",
                run.id(),
                step);
    }
}
