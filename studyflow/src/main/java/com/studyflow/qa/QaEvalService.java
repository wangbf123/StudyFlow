package com.studyflow.qa;

import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.rag.RagProperties;
import com.studyflow.rag.RagRetriever;
import com.studyflow.rag.RetrievedChunk;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class QaEvalService {

    private final KnowledgeBaseService knowledgeBaseService;
    private final RagRetriever ragRetriever;
    private final RagProperties ragProperties;

    public List<QaEvalQuestionRequest> samples() {
        return List.of(
                new QaEvalQuestionRequest(
                        "Why is Redis fast and where is it used in StudyFlow?",
                        List.of(),
                        List.of("Redis", "rate limit")
                ),
                new QaEvalQuestionRequest(
                        "How does StudyFlow build the RAG prompt?",
                        List.of(),
                        List.of("chunk", "Prompt")
                ),
                new QaEvalQuestionRequest(
                        "Why should RAG answers keep citation sources?",
                        List.of(),
                        List.of("citation", "source")
                ),
                new QaEvalQuestionRequest(
                        "What problem does the Redisson lock solve?",
                        List.of(),
                        List.of("Redisson", "lock")
                )
        );
    }

    public QaEvalResponse evaluate(Long userId, QaEvalRequest request) {
        knowledgeBaseService.getOwnedActive(userId, request.knowledgeBaseId());
        List<QaEvalQuestionResponse> results = request.questions().stream()
                .map(question -> evaluateQuestion(userId, request.knowledgeBaseId(), question))
                .toList();
        return new QaEvalResponse(
                request.knowledgeBaseId(),
                results.size(),
                rate(results.stream().map(QaEvalQuestionResponse::chunkHit).toList()),
                rate(results.stream().map(QaEvalQuestionResponse::keywordHit).toList()),
                results
        );
    }

    private QaEvalQuestionResponse evaluateQuestion(
            Long userId,
            Long knowledgeBaseId,
            QaEvalQuestionRequest question
    ) {
        int topK = Math.max(ragProperties.getTopK(), 1);
        List<RetrievedChunk> chunks = ragRetriever.retrieve(userId, knowledgeBaseId, question.question(), topK);
        List<Long> retrievedIds = chunks.stream()
                .map(RetrievedChunk::chunkId)
                .toList();
        Boolean chunkHit = chunkHit(question.expectedChunkIds(), retrievedIds);
        Boolean keywordHit = keywordHit(question.expectedKeywords(), chunks);
        return new QaEvalQuestionResponse(
                question.question(),
                chunks.size(),
                retrievedIds,
                chunkHit,
                keywordHit,
                chunks.stream()
                        .map(chunk -> QaRetrievedChunkResponse.from(chunk, true))
                        .toList()
        );
    }

    private Boolean chunkHit(List<Long> expectedChunkIds, List<Long> retrievedIds) {
        if (expectedChunkIds == null || expectedChunkIds.isEmpty()) {
            return null;
        }
        return retrievedIds.stream().anyMatch(expectedChunkIds::contains);
    }

    private Boolean keywordHit(List<String> expectedKeywords, List<RetrievedChunk> chunks) {
        if (expectedKeywords == null || expectedKeywords.isEmpty()) {
            return null;
        }
        String haystack = chunks.stream()
                .map(chunk -> chunk.fileName() + "\n" + chunk.content())
                .collect(java.util.stream.Collectors.joining("\n"))
                .toLowerCase(Locale.ROOT);
        return expectedKeywords.stream()
                .filter(Objects::nonNull)
                .map(keyword -> keyword.toLowerCase(Locale.ROOT).trim())
                .filter(keyword -> !keyword.isBlank())
                .allMatch(haystack::contains);
    }

    private Double rate(List<Boolean> values) {
        List<Boolean> concreteValues = values.stream()
                .filter(Objects::nonNull)
                .toList();
        if (concreteValues.isEmpty()) {
            return null;
        }
        long hits = concreteValues.stream().filter(Boolean::booleanValue).count();
        return Math.round(((double) hits / concreteValues.size()) * 10000.0) / 10000.0;
    }
}
