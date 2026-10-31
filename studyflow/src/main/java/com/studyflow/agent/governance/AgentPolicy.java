package com.studyflow.agent.governance;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.*;
import com.studyflow.common.BusinessException;
import com.studyflow.kb.KnowledgeBaseService;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.*;

@Component
@RequiredArgsConstructor
public class AgentPolicy {
    private final KnowledgeBaseService bases;

    public void check(Claim claim) {
        for (Long kb : claim.state().scope(claim.knowledgeBaseId()))
            bases.getOwnedActive(claim.userId(), kb);
    }

    public void subset(List<Long> requested, List<Long> permitted) {
        if (requested.isEmpty() || !permitted.containsAll(requested))
            throw new BusinessException(403, "知识范围不能扩大");
    }

    public void content(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max)
            throw new BusinessException(400, "内容为空或超出限制");
    }
}
