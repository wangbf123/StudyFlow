package com.studyflow.rag;

/** Offsets use UTF-16 code units, matching Java String and JavaScript slice. */
public record EvidenceSpan(RetrievedChunk chunk, String sourceId, String quote,
                           int start, int end, String contentHash, Long generation) {
    public EvidenceSpan withGeneration(Long value) {
        return new EvidenceSpan(chunk, sourceId, quote, start, end, contentHash, value);
    }
}
