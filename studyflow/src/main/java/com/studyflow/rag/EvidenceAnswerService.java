package com.studyflow.rag;

import com.studyflow.ai.AiProperties;
import com.studyflow.document.*;
import com.studyflow.kb.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class EvidenceAnswerService {
    private static final String REFUSAL = "当前资料不足以支持可验证的回答。请补充相关资料或调整问题。";
    private final EvidenceAnswerValidator validator;
    private final EvidenceVerifier verifier;
    private final AiProperties aiProperties;
    private final DocumentChunkMapper chunkMapper;
    private final DocumentMapper documentMapper;
    private final KnowledgeBaseService knowledgeBaseService;

    public VerifiedAnswer verify(String raw, RagPrompt prompt, Long userId, Long knowledgeBaseId) {
        knowledgeBaseService.getOwnedActive(userId, knowledgeBaseId);
        EvidenceValidation parsed = validator.validate(raw, prompt);
        List<GroundedClaim> owned = new ArrayList<>();
        int rejected = parsed.rejected();
        for (GroundedClaim claim : parsed.claims()) {
            List<EvidenceSpan> checked = new ArrayList<>();
            boolean valid = true;
            for (EvidenceSpan span : claim.evidence()) {
                DocumentChunkEntity chunk = chunkMapper.selectById(span.chunk().chunkId());
                DocumentEntity doc = chunk == null ? null : documentMapper.selectById(chunk.getDocumentId());
                if (!current(chunk, doc, span, userId, knowledgeBaseId)) { valid = false; break; }
                checked.add(span.withGeneration(chunk.getGeneration()));
            }
            if (valid) owned.add(new GroundedClaim(claim.index(), claim.text(), List.copyOf(checked)));
            else rejected++;
        }
        boolean extractive = "mock".equalsIgnoreCase(aiProperties.getProvider());
        EvidenceVerifier.Verification result = extractive ? null : verifier.verify(owned);
        knowledgeBaseService.getOwnedActive(userId, knowledgeBaseId);
        List<GroundedClaim> accepted = new ArrayList<>();
        for (GroundedClaim claim : owned) {
            boolean supported = extractive
                    ? claim.evidence().stream().allMatch(span -> claim.text().equals(span.quote()))
                    : "SUPPORTED".equals(result.verdicts().get(claim.index()));
            // Check again after remote verification: rebuild/deletion must invalidate a stale answer.
            if (supported) supported = claim.evidence().stream().allMatch(span -> current(
                    chunkMapper.selectById(span.chunk().chunkId()), documentMapper.selectById(span.chunk().documentId()),
                    span, userId, knowledgeBaseId));
            if (supported) accepted.add(claim); else rejected++;
        }
        String status = accepted.isEmpty() ? (result != null && result.failed() ? "VERIFICATION_UNAVAILABLE" : "ABSTAINED")
                : (extractive ? "EXTRACTED" : "SUPPORTED");
        String answer = accepted.isEmpty() ? ("VERIFICATION_UNAVAILABLE".equals(status)
                ? "证据核验服务暂不可用，未发布未经核验的结论。请稍后重试。" : REFUSAL)
                : accepted.stream().map(claim -> claim.text() + " " + claim.evidence().stream()
                        .map(span -> "[" + span.sourceId() + "]").distinct().reduce((a,b) -> a+" "+b).orElse(""))
                        .reduce((a,b) -> a+"\n\n"+b).orElse(REFUSAL);
        return new VerifiedAnswer(answer, status, extractive ? "EXTRACTIVE_ONLY" : "MODEL_VERIFIED",
                List.copyOf(accepted), rejected, parsed.structured(), result == null ? null : result.model(),
                result == null ? Integer.valueOf(0) : result.promptTokens(),
                result == null ? Integer.valueOf(0) : result.completionTokens(),
                result == null ? 0 : result.latencyMs());
    }

    private boolean current(DocumentChunkEntity chunk, DocumentEntity doc, EvidenceSpan span, Long userId, Long kbId) {
        return chunk != null && doc != null && Objects.equals(userId, chunk.getUserId())
                && Objects.equals(userId, doc.getUserId()) && Objects.equals(kbId, chunk.getKnowledgeBaseId())
                && Objects.equals(kbId, doc.getKnowledgeBaseId()) && Objects.equals(doc.getId(), span.chunk().documentId())
                && DocumentStatus.SUCCESS.equals(doc.getParseStatus())
                && Objects.equals(doc.getGeneration(), chunk.getGeneration())
                && (span.generation() == null || Objects.equals(span.generation(), chunk.getGeneration()))
                && chunk.getContent() != null && span.contentHash().equals(EvidenceAnswerValidator.sha256(chunk.getContent()));
    }
}
