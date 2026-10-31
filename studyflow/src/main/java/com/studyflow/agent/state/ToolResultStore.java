package com.studyflow.agent.state;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.*;
import com.studyflow.agent.context.TokenCounter;
import com.studyflow.common.BusinessException;
import com.studyflow.rag.EvidenceAnswerValidator;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ToolResultStore {
    private final JdbcTemplate jdbc;
    private final TokenCounter tokens;
    private final HarnessProperties properties;

    public String save(Claim run, int step, String tool, String content) {
        String bounded =
                AgentJson.clip(content, Math.clamp(properties.getArtifactChars(), 4000, 256000));
        jdbc.update(
                "INSERT INTO"
                    + " agent_tool_result(run_id,step_no,tool_name,content,content_hash,token_count,create_time)"
                    + " VALUES (?,?,?,?,?,?,NOW(6)) ON DUPLICATE KEY UPDATE run_id=run_id",
                run.id(),
                step,
                tool,
                bounded,
                EvidenceAnswerValidator.sha256(bounded),
                tokens.count(bounded));
        return "tool-result:" + run.id() + ":" + step;
    }

    public Map<String, Object> read(Claim run, int step, int start, int size) {
        var rows =
                jdbc.query(
                        "SELECT content,content_hash,token_count FROM agent_tool_result WHERE"
                                + " run_id=? AND step_no=?",
                        (rs, n) ->
                                Map.of(
                                        "content",
                                        rs.getString(1),
                                        "hash",
                                        rs.getString(2),
                                        "tokens",
                                        rs.getInt(3)),
                        run.id(),
                        step);
        if (rows.isEmpty()) throw new BusinessException(404, "工具结果不存在");
        String value = (String) rows.getFirst().get("content");
        int from = Math.clamp(start, 0, value.length()),
                to = Math.min(value.length(), from + Math.clamp(size, 100, 6000));
        return Map.of(
                "text",
                value.substring(from, to),
                "start",
                from,
                "next",
                to,
                "complete",
                to == value.length(),
                "sha256",
                rows.getFirst().get("hash"));
    }
}
