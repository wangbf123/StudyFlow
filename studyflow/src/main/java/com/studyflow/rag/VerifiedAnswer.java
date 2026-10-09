package com.studyflow.rag;

import java.util.List;

public record VerifiedAnswer(String answer, String status, String mode, List<GroundedClaim> claims,
                             int rejectedClaims, boolean structured, String verifierModel,
                             Integer verificationPromptTokens, Integer verificationCompletionTokens,
                             long verificationLatencyMs) {
    public List<RetrievedChunk> citedChunks() {
        return claims.stream().flatMap(claim -> claim.evidence().stream()).map(EvidenceSpan::chunk).distinct().toList();
    }
}
