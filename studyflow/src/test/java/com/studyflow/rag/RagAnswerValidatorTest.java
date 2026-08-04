package com.studyflow.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagAnswerValidatorTest {

    private final RagProperties properties = new RagProperties();
    private final RagAnswerValidator validator = new RagAnswerValidator(new ObjectMapper(), properties);

    @Test
    void keepsOnlyCitationsFromCurrentPromptContext() {
        RagPrompt prompt = promptWithChunks();
        String raw = """
                {
                  "answer": "Redis is used for cache and rate limiting [S1] [S9].",
                  "citations": [
                    {"source_id": "S1"},
                    {"source_id": "S9"},
                    {"chunk_id": 202}
                  ]
                }
                """;

        RagAnswer answer = validator.parseAndValidate(raw, prompt);

        assertTrue(answer.structuredOutput());
        assertEquals("Redis is used for cache and rate limiting [S1].", answer.answer());
        assertEquals(List.of(101L, 202L), answer.citedChunks().stream().map(RetrievedChunk::chunkId).toList());
        assertEquals(1, answer.rejectedCitationCount());
    }

    @Test
    void extractsValidInlineMarkersWhenJsonParsingFails() {
        RagAnswer answer = validator.parseAndValidate("Legacy answer with [S2] and [S99].", promptWithChunks());

        assertFalse(answer.structuredOutput());
        assertEquals("Legacy answer with [S2] and.", answer.answer());
        assertEquals(List.of(202L), answer.citedChunks().stream().map(RetrievedChunk::chunkId).toList());
    }

    @Test
    void supportsMarkdownFencedJson() {
        String raw = """
                ```json
                {"answer":"Use the indexed source [S2].","citations":[{"source_id":"S2"}]}
                ```
                """;

        RagAnswer answer = validator.parseAndValidate(raw, promptWithChunks());

        assertTrue(answer.structuredOutput());
        assertEquals("Use the indexed source [S2].", answer.answer());
        assertEquals(List.of(202L), answer.citedChunks().stream().map(RetrievedChunk::chunkId).toList());
    }

    private RagPrompt promptWithChunks() {
        return new RagPrompt("system", "user", List.of(
                chunk(101L, 10, "Redis cache and rate limiting"),
                chunk(202L, 20, "RAG citation validation")
        ));
    }

    private RetrievedChunk chunk(Long chunkId, Integer chunkIndex, String content) {
        return new RetrievedChunk(
                chunkId,
                chunkId + 1000,
                1L,
                "demo.md",
                chunkIndex,
                content,
                1.0,
                0.9,
                0.8,
                "HYBRID",
                0.95
        );
    }
}
