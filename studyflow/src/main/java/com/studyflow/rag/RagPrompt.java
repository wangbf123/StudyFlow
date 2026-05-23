package com.studyflow.rag;

import java.util.List;

public record RagPrompt(
        String systemPrompt,
        String userPrompt,
        List<RetrievedChunk> includedChunks
) {
}
