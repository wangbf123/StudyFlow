package com.studyflow.qa;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.ai.AiChatRequest;
import com.studyflow.ai.AiChatResponse;
import com.studyflow.ai.AiClient;
import com.studyflow.ai.AiProperties;
import com.studyflow.common.BusinessException;
import com.studyflow.document.DocumentChunkEntity;
import com.studyflow.document.DocumentChunkMapper;
import com.studyflow.kb.KnowledgeBaseEntity;
import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.rag.RagPrompt;
import com.studyflow.rag.RagPromptBuilder;
import com.studyflow.rag.RagProperties;
import com.studyflow.rag.RagRetriever;
import com.studyflow.rag.RetrievedChunk;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Service
public class QaService {

    private static final String NO_CONTEXT_ANSWER = "No relevant material was found in this knowledge base. Upload and parse related documents first, or try a more specific question.";

    private final KnowledgeBaseService knowledgeBaseService;
    private final QaSessionMapper qaSessionMapper;
    private final QaMessageMapper qaMessageMapper;
    private final QaCitationMapper qaCitationMapper;
    private final RagRetriever ragRetriever;
    private final RagPromptBuilder ragPromptBuilder;
    private final RagProperties ragProperties;
    private final AiClient aiClient;
    private final AiProperties aiProperties;
    private final DocumentChunkMapper documentChunkMapper;
    private final Executor qaTaskExecutor;

    public QaService(
            KnowledgeBaseService knowledgeBaseService,
            QaSessionMapper qaSessionMapper,
            QaMessageMapper qaMessageMapper,
            QaCitationMapper qaCitationMapper,
            RagRetriever ragRetriever,
            RagPromptBuilder ragPromptBuilder,
            RagProperties ragProperties,
            AiClient aiClient,
            AiProperties aiProperties,
            DocumentChunkMapper documentChunkMapper,
            @Qualifier("qaTaskExecutor") Executor qaTaskExecutor
    ) {
        this.knowledgeBaseService = knowledgeBaseService;
        this.qaSessionMapper = qaSessionMapper;
        this.qaMessageMapper = qaMessageMapper;
        this.qaCitationMapper = qaCitationMapper;
        this.ragRetriever = ragRetriever;
        this.ragPromptBuilder = ragPromptBuilder;
        this.ragProperties = ragProperties;
        this.aiClient = aiClient;
        this.aiProperties = aiProperties;
        this.documentChunkMapper = documentChunkMapper;
        this.qaTaskExecutor = qaTaskExecutor;
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
        List<RetrievedChunk> retrievedChunks = ragRetriever.retrieve(userId, request.knowledgeBaseId(), request.question());
        RagPrompt prompt = ragPromptBuilder.build(request.question(), retrievedChunks);
        List<Long> includedIds = prompt.includedChunks().stream()
                .map(RetrievedChunk::chunkId)
                .toList();
        List<QaRetrievedChunkResponse> chunks = retrievedChunks.stream()
                .map(chunk -> QaRetrievedChunkResponse.from(chunk, includedIds.contains(chunk.chunkId())))
                .toList();
        return new QaRetrieveResponse(
                request.knowledgeBaseId(),
                request.question(),
                Math.max(ragProperties.getTopK(), 1),
                Math.max(ragProperties.getMaxContextChars(), 1000),
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
                QaAskResponse response = ask(userId, request);
                send(emitter, "metadata", response);
                streamAnswer(emitter, response.answer());
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
        if (chunk == null) {
            throw new BusinessException("Citation chunk not found or access denied");
        }
        return new QaCitationDetailResponse(
                citation.getId(),
                citation.getMessageId(),
                citation.getDocumentId(),
                citation.getChunkId(),
                citation.getChunkIndex(),
                citation.getDocumentName(),
                chunk.getContent(),
                citation.getContentPreview(),
                citation.getScore()
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

        List<RetrievedChunk> retrievedChunks = ragRetriever.retrieve(userId, knowledgeBase.getId(), request.question());
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
                    assistantMessage.getModelName(),
                    0,
                    0,
                    0L,
                    List.of()
            );
        }

        RagPrompt prompt = ragPromptBuilder.build(request.question(), retrievedChunks);
        long start = System.currentTimeMillis();
        try {
            AiChatResponse aiResponse = aiClient.chat(new AiChatRequest(
                    prompt.systemPrompt(),
                    prompt.userPrompt(),
                    request.question(),
                    aiProperties.getModel(),
                    aiProperties.getTemperature()
            ));
            long latencyMs = System.currentTimeMillis() - start;
            QaMessageEntity assistantMessage = saveMessage(
                    session,
                    QaMessageRole.ASSISTANT,
                    QaMessageStatus.SUCCESS,
                    aiResponse.content(),
                    aiResponse.model(),
                    aiResponse.promptTokens(),
                    aiResponse.completionTokens(),
                    latencyMs,
                    null
            );
            List<QaCitationResponse> citations = saveCitations(assistantMessage.getId(), prompt.includedChunks());
            touchSession(session);
            return new QaAskResponse(
                    session.getId(),
                    userMessage.getId(),
                    assistantMessage.getId(),
                    aiResponse.content(),
                    aiResponse.model(),
                    aiResponse.promptTokens(),
                    aiResponse.completionTokens(),
                    latencyMs,
                    citations
            );
        } catch (Exception ex) {
            long latencyMs = System.currentTimeMillis() - start;
            QaMessageEntity failedMessage = saveMessage(
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
            saveCitations(failedMessage.getId(), prompt.includedChunks());
            touchSession(session);
            if (ex instanceof BusinessException businessException) {
                throw businessException;
            }
            throw new BusinessException(502, "AI service failed: " + ex.getMessage());
        }
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
        return session;
    }

    private QaMessageEntity getOwnedMessage(Long userId, Long messageId) {
        QaMessageEntity message = qaMessageMapper.selectOne(Wrappers.<QaMessageEntity>lambdaQuery()
                .eq(QaMessageEntity::getId, messageId)
                .eq(QaMessageEntity::getUserId, userId));
        if (message == null) {
            throw new BusinessException("QA message not found or access denied");
        }
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

    private List<QaCitationResponse> saveCitations(Long messageId, List<RetrievedChunk> chunks) {
        return chunks.stream()
                .map(chunk -> {
                    QaCitationEntity citation = new QaCitationEntity();
                    citation.setMessageId(messageId);
                    citation.setDocumentId(chunk.documentId());
                    citation.setChunkId(chunk.chunkId());
                    citation.setChunkIndex(chunk.chunkIndex());
                    citation.setDocumentName(chunk.fileName());
                    citation.setContentPreview(preview(chunk.content(), ragProperties.getCitationPreviewChars()));
                    citation.setScore(chunk.score());
                    qaCitationMapper.insert(citation);
                    return QaCitationResponse.from(citation);
                })
                .toList();
    }

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
                citations
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

    private void streamAnswer(SseEmitter emitter, String answer) throws IOException, InterruptedException {
        String safeAnswer = answer == null ? "" : answer;
        int step = 48;
        for (int i = 0; i < safeAnswer.length(); i += step) {
            String delta = safeAnswer.substring(i, Math.min(i + step, safeAnswer.length()));
            send(emitter, "delta", new QaStreamEventResponse("delta", delta));
            Thread.sleep(25);
        }
    }

    private void send(SseEmitter emitter, String eventName, Object data) throws IOException {
        emitter.send(SseEmitter.event()
                .name(eventName)
                .data(data));
    }
}
