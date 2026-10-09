package com.studyflow.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.ai.*;
import com.studyflow.document.*;
import org.junit.jupiter.api.*;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvidenceAnswerSecurityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final EvidenceProperties props = new EvidenceProperties();
    private final EvidenceAnswerValidator validator = new EvidenceAnswerValidator(mapper, props);
    private final String content = "📚 Redis uses an atomic Lua script for rate limiting.\nIt is not a relational database.";
    private final RetrievedChunk chunk = new RetrievedChunk(10L, 20L, 1L, "guide.md", 0, content, 1.0, 1.0, 1.0, "HYBRID", 1.0);
    private final RagPrompt prompt = new RagPrompt("", "", List.of(chunk));
    private final AiClient ai = mock(AiClient.class);
    private final AiProperties aiProps = new AiProperties();
    private final DocumentChunkMapper chunks = mock(DocumentChunkMapper.class);
    private final DocumentMapper docs = mock(DocumentMapper.class);
    private final EvidenceVerifier verifier = new EvidenceVerifier(ai, aiProps, props, mapper);
    private final EvidenceAnswerService service = new EvidenceAnswerService(validator, verifier, aiProps, chunks, docs, mock(com.studyflow.kb.KnowledgeBaseService.class));

    @BeforeEach void prepare() {
        DocumentChunkEntity c = new DocumentChunkEntity();
        c.setId(10L); c.setDocumentId(20L); c.setUserId(7L); c.setKnowledgeBaseId(1L); c.setGeneration(1L); c.setContent(content);
        DocumentEntity d = new DocumentEntity();
        d.setId(20L); d.setUserId(7L); d.setKnowledgeBaseId(1L); d.setGeneration(1L); d.setParseStatus(DocumentStatus.SUCCESS);
        when(chunks.selectById(10L)).thenReturn(c); when(docs.selectById(20L)).thenReturn(d);
    }
    @AfterEach void close() { verifier.close(); }
    private String raw(String text, String source, String quote) throws Exception {
        return mapper.writeValueAsString(java.util.Map.of("claims", List.of(java.util.Map.of("text", text, "evidence",
                List.of(java.util.Map.of("source_id", source, "quote", quote))))));
    }
    @Test void refusesAValidSourceWithFabricatedQuotation() throws Exception {
        assertTrue(validator.validate(raw("Redis supports SQL joins", "S1", "Redis supports SQL joins"), prompt).claims().isEmpty());
    }
    @Test void refusesSourcesOutsideCurrentContext() throws Exception {
        assertTrue(validator.validate(raw("claim", "S2", content), prompt).claims().isEmpty());
    }
    @Test void doesNotAcceptLegacyProseAsVerifiedAnswer() {
        assertFalse(validator.validate("Legacy answer [S1]", prompt).structured());
        assertEquals("ABSTAINED", service.verify("Legacy answer [S1]", prompt, 7L, 1L).status());
    }
    @Test void rejectsTrailingJsonAndUnknownSchemaFields() throws Exception {
        String good = raw(content, "S1", content);
        assertFalse(validator.validate(good + " {}", prompt).structured());
        assertFalse(validator.validate(good.substring(0, good.length()-1)+",\"answer\":\"bypass\"}", prompt).structured());
    }
    @Test void duplicateJsonFieldsCannotOverrideEvidenceProtocol() {
        assertFalse(validator.validate("{\"claims\":[],\"claims\":[]}", prompt).structured());
    }
    @Test void duplicateVerifierVerdictsFailClosed() throws Exception {
        aiProps.setProvider("openai-compatible");
        when(ai.chat(any())).thenReturn(new AiChatResponse("{\"verdicts\":[{\"index\":0,\"status\":\"CONTRADICTED\",\"status\":\"SUPPORTED\"}]}", "test", 1, 1));
        assertEquals("VERIFICATION_UNAVAILABLE", service.verify(raw(content, "S1", content), prompt, 7L, 1L).status());
    }
    @Test void offsetsRemainCorrectForEmojiAndNewlines() throws Exception {
        String quote = "Redis uses an atomic Lua script for rate limiting.";
        EvidenceSpan span = validator.validate(raw(quote, "S1", quote), prompt).claims().getFirst().evidence().getFirst();
        assertEquals(3, span.start());
        assertEquals(quote, content.substring(span.start(), span.end()));
        assertEquals(EvidenceAnswerValidator.sha256(content), span.contentHash());
    }
    @Test void mockCannotCertifyAnUnsupportedParaphrase() throws Exception {
        VerifiedAnswer result = service.verify(raw("Redis supports relational joins", "S1", content), prompt, 7L, 1L);
        assertEquals("ABSTAINED", result.status()); assertTrue(result.claims().isEmpty()); verifyNoInteractions(ai);
    }
    @Test void mockExtractiveAnswerIsClearlyLabelled() throws Exception {
        VerifiedAnswer result = service.verify(raw(content, "S1", content), prompt, 7L, 1L);
        assertEquals("EXTRACTED", result.status()); assertEquals("EXTRACTIVE_ONLY", result.mode());
        assertEquals(1L, result.claims().getFirst().evidence().getFirst().generation());
    }
    @Test void wrongOwnerOrRetiredGenerationCannotPublish() throws Exception {
        assertEquals("ABSTAINED", service.verify(raw(content, "S1", content), prompt, 99L, 1L).status());
        docs.selectById(20L).setGeneration(2L);
        assertEquals("ABSTAINED", service.verify(raw(content, "S1", content), prompt, 7L, 1L).status());
    }
    @Test void contradictedConclusionDoesNotPublishEvenWhenQuoteExists() throws Exception {
        aiProps.setProvider("openai-compatible");
        when(ai.chat(any())).thenReturn(new AiChatResponse("{\"verdicts\":[{\"index\":0,\"status\":\"CONTRADICTED\"}]}", "test-verifier", 10, 3));
        VerifiedAnswer result = service.verify(raw("Redis is relational", "S1", content), prompt, 7L, 1L);
        assertEquals("ABSTAINED", result.status()); assertFalse(result.answer().contains("Redis is relational"));
    }
    @Test void validVerifierResultCarriesSeparateUsageMetadata() throws Exception {
        aiProps.setProvider("openai-compatible"); props.setVerifierModel("separate-verifier");
        when(ai.chat(any())).thenReturn(new AiChatResponse("{\"verdicts\":[{\"index\":0,\"status\":\"SUPPORTED\"}]}", "separate-verifier", 10, 3));
        VerifiedAnswer result = service.verify(raw("Redis uses Lua for rate limiting", "S1", content), prompt, 7L, 1L);
        assertEquals("SUPPORTED", result.status()); assertEquals(10, result.verificationPromptTokens());
        assertEquals("separate-verifier", result.verifierModel());
        verify(ai).chat(argThat(request -> request.temperature()==0.0 && request.model().equals("separate-verifier")));
    }
    @Test void malformedOrMismatchedVerifierResultFailsClosed() throws Exception {
        aiProps.setProvider("openai-compatible");
        when(ai.chat(any())).thenReturn(new AiChatResponse("{\"verdicts\":[{\"index\":900,\"status\":\"SUPPORTED\"}]}", "test", 1, 1));
        assertEquals("VERIFICATION_UNAVAILABLE", service.verify(raw(content, "S1", content), prompt, 7L, 1L).status());
    }
    @Test void changedEvidenceWhileVerifierRunsInvalidatesAnswer() throws Exception {
        aiProps.setProvider("openai-compatible");
        when(ai.chat(any())).thenAnswer(invocation -> {
            docs.selectById(20L).setParseStatus(DocumentStatus.DELETED);
            return new AiChatResponse("{\"verdicts\":[{\"index\":0,\"status\":\"SUPPORTED\"}]}", "test", 1, 1);
        });
        assertEquals("ABSTAINED", service.verify(raw(content, "S1", content), prompt, 7L, 1L).status());
    }
    @Test void verifierTimeoutNeverReleasesUnverifiedClaims() throws Exception {
        aiProps.setProvider("openai-compatible"); props.setVerifierTimeoutSeconds(1);
        CountDownLatch release = new CountDownLatch(1);
        when(ai.chat(any())).thenAnswer(invocation -> { release.await(); return null; });
        try {
            assertEquals("VERIFICATION_UNAVAILABLE", service.verify(raw(content, "S1", content), prompt, 7L, 1L).status());
        } finally { release.countDown(); }
    }
    @Test void actualMockGeneratorProducesOnlyExactExtracts() {
        RagPrompt generated = new RagPromptBuilder(new RagProperties()).build("How is Redis used?", List.of(chunk));
        AiChatResponse response = new MockAiClient().chat(new AiChatRequest(generated.systemPrompt(), generated.userPrompt(), "q", "mock", 0.0));
        EvidenceValidation checked = validator.validate(response.content(), generated);
        assertEquals(1, checked.claims().size());
        assertEquals(checked.claims().getFirst().text(), checked.claims().getFirst().evidence().getFirst().quote());
    }
}
