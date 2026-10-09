package com.studyflow.qa;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.ai.AiChatRequest;
import com.studyflow.ai.AiChatResponse;
import com.studyflow.ai.AiClient;
import com.studyflow.ai.AiProperties;
import com.studyflow.ai.AiStreamChunk;
import com.studyflow.common.BusinessException;
import com.studyflow.document.DocumentChunkEntity;
import com.studyflow.document.DocumentChunkMapper;
import com.studyflow.kb.KnowledgeBaseEntity;
import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.rag.VerifiedAnswer;
import com.studyflow.rag.EvidenceAnswerService;
import com.studyflow.rag.EvidenceAnswerValidator;
import com.studyflow.rag.GroundedClaim;
import com.studyflow.rag.EvidenceSpan;
import com.studyflow.document.DocumentMapper;
import com.studyflow.document.DocumentEntity;
import com.studyflow.document.DocumentStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.Objects;
import com.studyflow.rag.RagAnswerValidator;
import com.studyflow.rag.RagPrompt;
import com.studyflow.rag.RagPromptBuilder;
import com.studyflow.rag.RagProperties;
import com.studyflow.rag.RagQueryRewriteResult;
import com.studyflow.rag.RagQueryRewriter;
import com.studyflow.rag.RagRetriever;
import com.studyflow.rag.RetrievedChunk;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class QaService {

    private static final String NO_CONTEXT_ANSWER = "No relevant material was found in this knowledge base. Upload and parse related documents first, or try a more specific question.";

    private final KnowledgeBaseService knowledgeBaseService;
    private final QaSessionMapper qaSessionMapper;
    private final QaMessageMapper qaMessageMapper;
    private final QaCitationMapper qaCitationMapper;
    private final RagRetriever ragRetriever;
    private final RagPromptBuilder ragPromptBuilder;
    private final RagQueryRewriter ragQueryRewriter;
    private final RagAnswerValidator ragAnswerValidator;
    private final RagProperties ragProperties;
    private final AiClient aiClient;
    private final AiProperties aiProperties;
    private final DocumentChunkMapper documentChunkMapper;
    private final Executor qaTaskExecutor;
    private final EvidenceAnswerService evidenceAnswerService;
    private final QaClaimMapper qaClaimMapper;
    private final DocumentMapper documentMapper;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate writeTransaction;

    public QaService(
            KnowledgeBaseService knowledgeBaseService,
            QaSessionMapper qaSessionMapper,
            QaMessageMapper qaMessageMapper,
            QaCitationMapper qaCitationMapper,
            RagRetriever ragRetriever,
            RagPromptBuilder ragPromptBuilder,
            RagQueryRewriter ragQueryRewriter,
            RagAnswerValidator ragAnswerValidator,
            RagProperties ragProperties,
            AiClient aiClient,
            AiProperties aiProperties,
            DocumentChunkMapper documentChunkMapper,
            EvidenceAnswerService evidenceAnswerService,
            QaClaimMapper qaClaimMapper,
            DocumentMapper documentMapper,
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            @Qualifier("qaTaskExecutor") Executor qaTaskExecutor
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.qaSessionMapper = qaSessionMapper;
        this.qaMessageMapper = qaMessageMapper;
        this.qaCitationMapper = qaCitationMapper;
        this.ragRetriever = ragRetriever;
        this.ragPromptBuilder = ragPromptBuilder;
        this.ragQueryRewriter = ragQueryRewriter;
        this.ragAnswerValidator = ragAnswerValidator;
        this.ragProperties = ragProperties;
        this.aiClient = aiClient;
        this.aiProperties = aiProperties;
        this.documentChunkMapper = documentChunkMapper;
        this.qaTaskExecutor = qaTaskExecutor;
        this.evidenceAnswerService = evidenceAnswerService;
        this.qaClaimMapper = qaClaimMapper;
        this.documentMapper = documentMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public QaSessionResponse createSession(Long userId, QaCreateSessionRequest request) {
        knowledgeBaseService.getOwnedActive(userId, request.knowledgeBaseId());
        QaSessionEntity session = new QaSessionEntity();
        session.setUserId(userId);
        session.setKnowledgeBaseId(request.knowledgeBaseId());
        session.setTitle(normalizeTitle(request.title(), "New QA session"));
        session.setStatus(QaSessionStatus.ACTIVE);
        qaSessionMapper.insert(session);
        return QaSessionResponse.from(session);
    }

    public List<QaSessionResponse> listSessions(Long userId, Long knowledgeBaseId) {
        if (knowledgeBaseId != null) {
            knowledgeBaseService.getOwnedActive(userId, knowledgeBaseId);
        }
        var wrapper = Wrappers.<QaSessionEntity>lambdaQuery()
                .eq(QaSessionEntity::getUserId, userId)
                .ne(QaSessionEntity::getStatus, QaSessionStatus.DELETED)
                .orderByDesc(QaSessionEntity::getUpdateTime);
        if (knowledgeBaseId != null) {
            wrapper.eq(QaSessionEntity::getKnowledgeBaseId, knowledgeBaseId);
        }
        return qaSessionMapper.selectList(wrapper).stream()
                .map(QaSessionResponse::from)
                .toList();
    }

    public List<QaMessageResponse> listMessages(Long userId, Long sessionId) {
        QaSessionEntity session = getOwnedSession(userId, sessionId);
        return qaMessageMapper.selectList(Wrappers.<QaMessageEntity>lambdaQuery()
                        .eq(QaMessageEntity::getSessionId, session.getId())
                        .orderByAsc(QaMessageEntity::getCreateTime))
                .stream()
                .map(this::toMessageResponse)
                .toList();
    }

    public QaRetrieveResponse retrieve(Long userId, QaRetrieveRequest request) {
        knowledgeBaseService.getOwnedActive(userId, request.knowledgeBaseId());
        RagQueryRewriteResult rewrite = ragQueryRewriter.rewrite(request.question());
        List<RetrievedChunk> retrievedChunks = ragRetriever.retrieve(userId, request.knowledgeBaseId(), rewrite.searchQuery());
        RagPrompt prompt = ragPromptBuilder.build(request.question(), rewrite.searchQuery(), retrievedChunks);
        List<Long> includedIds = prompt.includedChunks().stream()
                .map(RetrievedChunk::chunkId)
                .toList();
        List<QaRetrievedChunkResponse> chunks = retrievedChunks.stream()
                .map(chunk -> QaRetrievedChunkResponse.from(chunk, includedIds.contains(chunk.chunkId())))
                .toList();
        return new QaRetrieveResponse(
                request.knowledgeBaseId(),
                request.question(),
                rewrite.searchQuery(),
                Math.max(ragProperties.getTopK(), 1),
                Math.max(ragProperties.getMaxContextChars(), 1000),
                retrievalStrategy(),
                chunks.size(),
                includedIds.size(),
                chunks
        );
    }

    public SseEmitter askStream(Long userId, QaAskRequest request) {
        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> {
            try {
                send(emitter, "message_start", new QaStreamEventResponse("message_start", request.question()));
                QaAskResponse response = askStreaming(userId, request, emitter);
                send(emitter, "metadata", response);
                send(emitter, "citations", response.citations());
                send(emitter, "message_end", response.assistantMessageId());
                emitter.complete();
            } catch (Exception ex) {
                try {
                    send(emitter, "error", new QaStreamEventResponse("error", ex.getMessage()));
                } catch (Exception ignored) {
                }
                emitter.completeWithError(ex);
            }
        }, qaTaskExecutor);
        return emitter;
    }

    public List<QaCitationResponse> listCitations(Long userId, Long messageId) {
        QaMessageEntity message = getOwnedMessage(userId, messageId);
        return qaCitationMapper.selectList(Wrappers.<QaCitationEntity>lambdaQuery()
                        .eq(QaCitationEntity::getMessageId, message.getId())
                        .orderByDesc(QaCitationEntity::getScore))
                .stream()
                .map(QaCitationResponse::from)
                .toList();
    }

    public QaCitationDetailResponse getCitationChunk(Long userId, Long citationId) {
        QaCitationEntity citation = qaCitationMapper.selectById(citationId);
        if (citation == null) {
            throw new BusinessException("Citation not found");
        }
        QaMessageEntity message = getOwnedMessage(userId, citation.getMessageId());
        DocumentChunkEntity chunk = documentChunkMapper.selectOne(Wrappers.<DocumentChunkEntity>lambdaQuery()
                .eq(DocumentChunkEntity::getId, citation.getChunkId())
                .eq(DocumentChunkEntity::getUserId, userId)
                .eq(DocumentChunkEntity::getKnowledgeBaseId, message.getKnowledgeBaseId()));
        DocumentEntity doc = documentMapper.selectById(citation.getDocumentId());
        if (doc == null || DocumentStatus.DELETED.equals(doc.getParseStatus())
                || !Objects.equals(doc.getUserId(), userId)
                || !Objects.equals(doc.getKnowledgeBaseId(), message.getKnowledgeBaseId())) {
            throw new BusinessException(404, "Citation document no longer available or access denied");
        }
        boolean current = chunk != null && (citation.getContentHash() == null
                || (Objects.equals(citation.getDocumentGeneration(), doc.getGeneration())
                && Objects.equals(chunk.getGeneration(), doc.getGeneration())
                && citation.getContentHash().equals(EvidenceAnswerValidator.sha256(chunk.getContent()))));
        if (chunk == null && citation.getQuote() == null) {
            throw new BusinessException(404, "Citation chunk no longer available");
        }
        return new QaCitationDetailResponse(
                citation.getId(),
                citation.getMessageId(),
                citation.getDocumentId(),
                citation.getChunkId(),
                citation.getChunkIndex(),
                citation.getPageNumber(),
                citation.getParserType(),
                chunk == null ? null : chunk.getOcrConfidence(),
                citation.getDocumentName(),
                current ? chunk.getContent() : citation.getQuote(),
                citation.getContentPreview(),
                citation.getScore(), citation.getQuote(), current ? citation.getCharStart() : null,
                current ? citation.getCharEnd() : null, citation.getDocumentGeneration(), citation.getContentHash(),
                citation.getVerificationStatus(), current ? "CURRENT" : "STALE_VERSION"
        );
    }

    public QaAskResponse ask(Long userId, QaAskRequest request) {
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseService.getOwnedActive(userId, request.knowledgeBaseId());
        QaSessionEntity session = resolveSession(userId, knowledgeBase, request);
        QaMessageEntity userMessage = saveMessage(
                session,
                QaMessageRole.USER,
                QaMessageStatus.SUCCESS,
                request.question(),
                null,
                null,
                null,
                null,
                null
        );

        RagQueryRewriteResult rewrite = ragQueryRewriter.rewrite(request.question());
        List<RetrievedChunk> retrievedChunks = ragRetriever.retrieve(userId, knowledgeBase.getId(), rewrite.searchQuery());
        if (retrievedChunks.isEmpty()) {
            QaMessageEntity assistantMessage = saveMessage(
                    session,
                    QaMessageRole.ASSISTANT,
                    QaMessageStatus.SUCCESS,
                    NO_CONTEXT_ANSWER,
                    "rule-no-context",
                    0,
                    0,
                    0L,
                    null
            );
            touchSession(session);
            return new QaAskResponse(
                    session.getId(),
                    userMessage.getId(),
                    assistantMessage.getId(),
                    NO_CONTEXT_ANSWER,
                    rewrite.searchQuery(),
                    assistantMessage.getModelName(),
                    0,
                    0,
                    0L,
                    false,
                    0,
                    0,
                    List.of()
            );
        }

        RagPrompt prompt = ragPromptBuilder.build(request.question(), rewrite.searchQuery(), retrievedChunks);
        long start = System.currentTimeMillis();
        try {
            AiChatResponse aiResponse = aiClient.chat(new AiChatRequest(
                    prompt.systemPrompt(),
                    prompt.userPrompt(),
                    request.question(),
                    aiProperties.getModel(),
                    aiProperties.getTemperature()
            ));
            VerifiedAnswer trustedAnswer = evidenceAnswerService.verify(aiResponse.content(), prompt, userId, knowledgeBase.getId());
            long latencyMs = System.currentTimeMillis() - start;
            PersistedAnswer saved = persistVerified(session, trustedAnswer, aiResponse.model(),
                    aiResponse.promptTokens(), aiResponse.completionTokens(), latencyMs);
            touchSession(session);
            return verifiedResponse(session, userMessage, saved, trustedAnswer, rewrite.searchQuery(),
                    aiResponse.model(), aiResponse.promptTokens(), aiResponse.completionTokens(), latencyMs);
        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - start;
            saveMessage(
                    session,
                    QaMessageRole.ASSISTANT,
                    QaMessageStatus.FAILED,
                    "AI service failed to generate an answer.",
                    aiProperties.getModel(),
                    null,
                    null,
                    latencyMs,
                    ex.getMessage()
            );
            touchSession(session);
            if (ex instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(502, "AI service failed: " + ex.getMessage());
        }
    }

    private QaAskResponse askStreaming(Long userId, QaAskRequest request, SseEmitter emitter) {
        KnowledgeBaseEntity knowledgeBase = knowledgeBaseService.getOwnedActive(userId, request.knowledgeBaseId());
        QaSessionEntity session = resolveSession(userId, knowledgeBase, request);
        QaMessageEntity userMessage = saveMessage(
                session,
                QaMessageRole.USER,
                QaMessageStatus.SUCCESS,
                request.question(),
                null,
                null,
                null,
                null,
                null
        );

        RagQueryRewriteResult rewrite = ragQueryRewriter.rewrite(request.question());
        List<RetrievedChunk> retrievedChunks = ragRetriever.retrieve(userId, knowledgeBase.getId(), rewrite.searchQuery());
        if (retrievedChunks.isEmpty()) {
            QaMessageEntity assistantMessage = saveMessage(
                    session,
                    QaMessageRole.ASSISTANT,
                    QaMessageStatus.SUCCESS,
                    NO_CONTEXT_ANSWER,
                    "rule-no-context",
                    0,
                    0,
                    0L,
                    null
            );
            touchSession(session);
            streamDelta(emitter, NO_CONTEXT_ANSWER);
            return new QaAskResponse(
                    session.getId(),
                    userMessage.getId(),
                    assistantMessage.getId(),
                    NO_CONTEXT_ANSWER,
                    rewrite.searchQuery(),
                    assistantMessage.getModelName(),
                    0,
                    0,
                    0L,
                    false,
                    0,
                    0,
                    List.of()
            );
        }

        RagPrompt prompt = ragPromptBuilder.build(request.question(), rewrite.searchQuery(), retrievedChunks);
        StringBuilder rawAnswer = new StringBuilder();
        AtomicReference<String> modelName = new AtomicReference<>(aiProperties.getModel());
        AtomicInteger promptTokens = new AtomicInteger(-1);
        AtomicInteger completionTokens = new AtomicInteger(-1);
        long start = System.currentTimeMillis();
        try {
            AiChatResponse aiResponse = aiClient.streamChat(new AiChatRequest(
                    prompt.systemPrompt(),
                    prompt.userPrompt(),
                    request.question(),
                    aiProperties.getModel(),
                    aiProperties.getTemperature()
            ), chunk -> handleAiStreamChunk(rawAnswer, modelName, promptTokens, completionTokens, chunk));
            if (rawAnswer.isEmpty() && aiResponse.content() != null) {
                rawAnswer.append(aiResponse.content());
            }
            if (aiResponse.model() != null) {
                modelName.set(aiResponse.model());
            }
            if (aiResponse.promptTokens() != null) {
                promptTokens.set(aiResponse.promptTokens());
            }
            if (aiResponse.completionTokens() != null) {
                completionTokens.set(aiResponse.completionTokens());
            }
            send(emitter, "verification_start", new QaStreamEventResponse("verification_start", "正在核对结论与原文证据"));
            VerifiedAnswer trustedAnswer = evidenceAnswerService.verify(rawAnswer.toString(), prompt, userId, knowledgeBase.getId());
            long latencyMs = System.currentTimeMillis() - start;
            PersistedAnswer saved = persistVerified(session, trustedAnswer, modelName.get(),
                    nullableToken(promptTokens), nullableToken(completionTokens), latencyMs);
            // Unverified provider output is never emitted or stored as a visible answer.
            streamDelta(emitter, trustedAnswer.answer());
            touchSession(session);
            return verifiedResponse(session, userMessage, saved, trustedAnswer, rewrite.searchQuery(),
                    modelName.get(), nullableToken(promptTokens), nullableToken(completionTokens), latencyMs);
        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - start;
            saveMessage(
                    session,
                    QaMessageRole.ASSISTANT,
                    QaMessageStatus.FAILED,
                    "AI service failed; no unverified answer was published.",
                    modelName.get(),
                    nullableToken(promptTokens),
                    nullableToken(completionTokens),
                    latencyMs,
                    ex.getMessage()
            );
            touchSession(session);
            if (ex instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(502, "AI stream service failed: " + ex.getMessage());
        }
    }

    private void handleAiStreamChunk(
            StringBuilder answer,
            AtomicReference<String> modelName,
            AtomicInteger promptTokens,
            AtomicInteger completionTokens,
            AiStreamChunk chunk
    ) {
        if (chunk == null) {
            return;
        }
        if (chunk.model() != null && !chunk.model().isBlank()) {
            modelName.set(chunk.model());
        }
        if (chunk.promptTokens() != null) {
            promptTokens.set(chunk.promptTokens());
        }
        if (chunk.completionTokens() != null) {
            completionTokens.set(chunk.completionTokens());
        }
        if (!chunk.done() && chunk.delta() != null && !chunk.delta().isEmpty()) {
            if (answer.length() + chunk.delta().length() > aiProperties.getMaxOutputChars()) {
                throw new BusinessException(502, "AI stream exceeds configured size limit");
            }
            answer.append(chunk.delta());
        }
    }

    private void streamDelta(SseEmitter emitter, String delta) {
        try {
            send(emitter, "delta", new QaStreamEventResponse("delta", delta));
        } catch (IOException ex) {
            throw new BusinessException(499, "SSE client disconnected");
        }
    }

    private Integer nullableToken(AtomicInteger value) {
        return value.get() < 0 ? null : value.get();
    }

    private QaSessionEntity resolveSession(Long userId, KnowledgeBaseEntity knowledgeBase, QaAskRequest request) {
        if (request.sessionId() != null) {
            QaSessionEntity session = getOwnedSession(userId, request.sessionId());
            if (!knowledgeBase.getId().equals(session.getKnowledgeBaseId())) {
                throw new BusinessException("QA session does not belong to the requested knowledge base");
            }
            return session;
        }
        QaSessionEntity session = new QaSessionEntity();
        session.setUserId(userId);
        session.setKnowledgeBaseId(knowledgeBase.getId());
        session.setTitle(normalizeTitle(request.question(), "QA session"));
        session.setStatus(QaSessionStatus.ACTIVE);
        qaSessionMapper.insert(session);
        return session;
    }

    private QaSessionEntity getOwnedSession(Long userId, Long sessionId) {
        QaSessionEntity session = qaSessionMapper.selectOne(Wrappers.<QaSessionEntity>lambdaQuery()
                .eq(QaSessionEntity::getId, sessionId)
                .eq(QaSessionEntity::getUserId, userId)
                .ne(QaSessionEntity::getStatus, QaSessionStatus.DELETED));
        if (session == null) {
            throw new BusinessException("QA session not found or access denied");
        }
        knowledgeBaseService.getOwnedActive(userId, session.getKnowledgeBaseId());
        return session;
    }

    private QaMessageEntity getOwnedMessage(Long userId, Long messageId) {
        QaMessageEntity message = qaMessageMapper.selectOne(Wrappers.<QaMessageEntity>lambdaQuery()
                .eq(QaMessageEntity::getId, messageId)
                .eq(QaMessageEntity::getUserId, userId));
        if (message == null) {
            throw new BusinessException("QA message not found or access denied");
        }
        knowledgeBaseService.getOwnedActive(userId, message.getKnowledgeBaseId());
        return message;
    }

    private QaMessageEntity saveMessage(
            QaSessionEntity session,
            String role,
            String status,
            String content,
            String modelName,
            Integer promptTokens,
            Integer completionTokens,
            Long latencyMs,
            String errorMessage
    ) {
        QaMessageEntity message = new QaMessageEntity();
        message.setSessionId(session.getId());
        message.setUserId(session.getUserId());
        message.setKnowledgeBaseId(session.getKnowledgeBaseId());
        message.setRole(role);
        message.setStatus(status);
        message.setContent(content);
        message.setModelName(modelName);
        message.setPromptTokens(promptTokens);
        message.setCompletionTokens(completionTokens);
        message.setLatencyMs(latencyMs);
        message.setErrorMessage(limit(errorMessage, 1000));
        qaMessageMapper.insert(message);
        return message;
    }

    private PersistedAnswer persistVerified(QaSessionEntity session, VerifiedAnswer answer, String model,
            Integer promptTokens, Integer completionTokens, Long latencyMs) {
        return writeTransaction.execute(status -> {
            // Share-lock source rows in stable order for the short persistence transaction.
            // A concurrent rebuild/deletion cannot commit between the final provenance check and saving.
            var spans = answer.claims().stream().flatMap(claim -> claim.evidence().stream())
                    .sorted(java.util.Comparator.comparing((EvidenceSpan span) -> span.chunk().documentId())
                            .thenComparing(span -> span.chunk().chunkId())).toList();
            for (Long documentId : spans.stream().map(span -> span.chunk().documentId()).distinct().sorted().toList()) {
                List<Long> locked = jdbcTemplate.queryForList("""
                        SELECT id FROM document WHERE id=? AND user_id=? AND knowledge_base_id=?
                        AND parse_status='SUCCESS' FOR SHARE
                        """, Long.class, documentId, session.getUserId(), session.getKnowledgeBaseId());
                if (locked.size() != 1) throw new BusinessException(409, "Evidence document changed; retry the question");
            }
            // Match pipeline's document -> knowledge-base -> chunk lock order.
            List<Long> ownedKb = jdbcTemplate.queryForList("""
                    SELECT id FROM knowledge_base WHERE id=? AND user_id=? AND status='ACTIVE' FOR SHARE
                    """, Long.class, session.getKnowledgeBaseId(), session.getUserId());
            if (ownedKb.size() != 1) throw new BusinessException(409, "Knowledge base changed during verification");
            for (EvidenceSpan span : spans) {
                List<String> current = jdbcTemplate.queryForList("""
                        SELECT c.content FROM document_chunk c
                        WHERE c.id=? AND c.user_id=? AND c.knowledge_base_id=? AND c.generation=?
                        AND EXISTS (SELECT 1 FROM document d WHERE d.id=c.document_id AND d.generation=c.generation)
                        FOR SHARE
                        """, String.class, span.chunk().chunkId(), session.getUserId(), session.getKnowledgeBaseId(), span.generation());
                if (current.size() != 1 || !span.contentHash().equals(EvidenceAnswerValidator.sha256(current.getFirst()))) {
                    throw new BusinessException(409, "Evidence changed during verification; retry the question");
                }
            }
            QaMessageEntity message = saveMessage(session, QaMessageRole.ASSISTANT, QaMessageStatus.SUCCESS,
                    answer.answer(), model, promptTokens, completionTokens, latencyMs, null);
            message.setAnswerStatus(answer.status());
            message.setVerificationMode(answer.mode());
            message.setVerifierModel(answer.verifierModel());
            message.setVerificationLatencyMs(answer.verificationLatencyMs());
            message.setVerificationPromptTokens(answer.verificationPromptTokens());
            message.setVerificationCompletionTokens(answer.verificationCompletionTokens());
            message.setRejectedClaimCount(answer.rejectedClaims());
            qaMessageMapper.updateById(message);
            List<QaCitationResponse> citations = new ArrayList<>();
            List<QaClaimResponse> claims = new ArrayList<>();
            for (GroundedClaim claim : answer.claims()) {
                QaClaimEntity entity = new QaClaimEntity();
                entity.setMessageId(message.getId());
                entity.setOrdinal(claim.index());
                entity.setText(claim.text());
                entity.setVerificationStatus(answer.status());
                qaClaimMapper.insert(entity);
                List<QaCitationResponse> evidence = new ArrayList<>();
                for (EvidenceSpan span : claim.evidence()) {
                    var chunk = span.chunk();
                    QaCitationEntity citation = new QaCitationEntity();
                    citation.setMessageId(message.getId());
                    citation.setClaimId(entity.getId());
                    citation.setSourceId(span.sourceId());
                    citation.setDocumentId(chunk.documentId());
                    citation.setChunkId(chunk.chunkId());
                    citation.setChunkIndex(chunk.chunkIndex());
                    citation.setPageNumber(chunk.pageNumber());
                    citation.setParserType(chunk.parserType());
                    citation.setDocumentName(chunk.fileName());
                    citation.setContentPreview(preview(span.quote(), Math.min(1000, ragProperties.getCitationPreviewChars())));
                    citation.setScore(chunk.score());
                    citation.setQuote(span.quote());
                    citation.setCharStart(span.start());
                    citation.setCharEnd(span.end());
                    citation.setContentHash(span.contentHash());
                    citation.setDocumentGeneration(span.generation());
                    citation.setVerificationStatus(answer.status());
                    qaCitationMapper.insert(citation);
                    evidence.add(QaCitationResponse.from(citation));
                }
                citations.addAll(evidence);
                claims.add(new QaClaimResponse(entity.getId(), entity.getText(), entity.getVerificationStatus(), List.copyOf(evidence)));
            }
            return new PersistedAnswer(message, List.copyOf(citations), List.copyOf(claims));
        });
    }

    private QaAskResponse verifiedResponse(QaSessionEntity session, QaMessageEntity userMessage,
            PersistedAnswer saved, VerifiedAnswer answer, String rewrittenQuery, String model,
            Integer promptTokens, Integer completionTokens, long latencyMs) {
        return new QaAskResponse(session.getId(), userMessage.getId(), saved.message().getId(), answer.answer(),
                rewrittenQuery, model, promptTokens, completionTokens, latencyMs, answer.structured(),
                saved.citations().size(), answer.rejectedClaims(), saved.citations(), answer.status(), answer.mode(),
                answer.verifierModel(), answer.verificationLatencyMs(), answer.verificationPromptTokens(),
                answer.verificationCompletionTokens(), answer.rejectedClaims(), saved.claims());
    }

    private record PersistedAnswer(QaMessageEntity message, List<QaCitationResponse> citations, List<QaClaimResponse> claims) { }

    private QaMessageResponse toMessageResponse(QaMessageEntity message) {
        List<QaCitationResponse> citations = qaCitationMapper.selectList(Wrappers.<QaCitationEntity>lambdaQuery()
                        .eq(QaCitationEntity::getMessageId, message.getId())
                        .orderByDesc(QaCitationEntity::getScore))
                .stream()
                .map(QaCitationResponse::from)
                .toList();
        return new QaMessageResponse(
                message.getId(),
                message.getSessionId(),
                message.getRole(),
                message.getStatus(),
                message.getContent(),
                message.getModelName(),
                message.getPromptTokens(),
                message.getCompletionTokens(),
                message.getLatencyMs(),
                message.getErrorMessage(),
                message.getCreateTime(),
                citations, message.getAnswerStatus(), message.getVerificationMode(), message.getVerifierModel(),
                message.getVerificationLatencyMs(), message.getVerificationPromptTokens(),
                message.getVerificationCompletionTokens(), message.getRejectedClaimCount(),
                qaClaimMapper.selectList(Wrappers.<QaClaimEntity>lambdaQuery()
                        .eq(QaClaimEntity::getMessageId, message.getId()).orderByAsc(QaClaimEntity::getOrdinal)).stream()
                        .map(claim -> new QaClaimResponse(claim.getId(), claim.getText(), claim.getVerificationStatus(),
                                citations.stream().filter(c -> Objects.equals(c.claimId(), claim.getId())).toList())).toList()
        );
    }

    private void touchSession(QaSessionEntity session) {
        qaSessionMapper.updateById(session);
    }

    private String normalizeTitle(String value, String fallback) {
        String title = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (title.isBlank()) {
            return fallback;
        }
        return limit(title, 120);
    }

    private String preview(String value, int maxLength) {
        String normalized = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        int safeMax = Math.max(maxLength, 80);
        return limit(normalized, safeMax);
    }

    private String limit(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(maxLength - 3, 1)) + "...";
    }

    private void send(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(data));
    }

    private String retrievalStrategy() {
        return ragProperties.isHybridEnabled() ? "HYBRID_BM25_VECTOR_RERANK" : "BM25";
    }
}
