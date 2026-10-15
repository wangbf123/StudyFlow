package com.studyflow.search;

import java.util.List;

/** Scores are ranking signals, never confidence probabilities. No prompts or secrets are exposed. */
public record SearchExplanation(String requestedMode, String effectiveMode, String embeddingProvider,
                                String embeddingModel, String degradationReason, int lexicalCandidates,
                                int vectorCandidates, int discardedCandidates, int verifiedCandidates,
                                int rrfConstant, double elapsedMs, List<ExplainedHit> hits) {
    public record ExplainedHit(SearchResponse result, Integer bm25Rank, Integer vectorRank,
                               double bm25RrfContribution, double vectorRrfContribution) {}
}
