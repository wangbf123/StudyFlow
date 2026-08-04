package com.studyflow.rag;

import java.util.List;

public record RagAnswer(
        String answer,
        List<RetrievedChunk> citedChunks,
        boolean structuredOutput,
        int rejectedCitationCount
) {
}
