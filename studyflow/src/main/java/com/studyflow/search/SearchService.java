package com.studyflow.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.document.DocumentChunkEntity;
import com.studyflow.document.DocumentChunkMapper;
import com.studyflow.document.DocumentEntity;
import com.studyflow.document.DocumentMapper;
import com.studyflow.embedding.EmbeddingClient;
import com.studyflow.embedding.EmbeddingProperties;
import com.studyflow.embedding.EmbeddingVectorCodec;
import com.studyflow.rag.RagProperties;
import com.studyflow.rag.RagReranker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final String INDEX = "studyflow_document_chunk";

    private final ElasticsearchClient elasticsearchClient;
    private final DocumentChunkMapper documentChunkMapper;
    private final DocumentMapper documentMapper;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingProperties embeddingProperties;
    private final EmbeddingVectorCodec embeddingVectorCodec;
    private final RagProperties ragProperties;
    private final RagReranker ragReranker;

    public void indexChunks(DocumentEntity document, List<DocumentChunkEntity> chunks) {
        for (DocumentChunkEntity chunk : chunks) {
            indexChunk(document, chunk);
        }
    }

    public void indexChunk(DocumentEntity document, DocumentChunkEntity chunk) {
        List<Double> vector = embeddingVectorCodec.decode(chunk.getEmbedding());
        DocumentChunkIndex index = new DocumentChunkIndex(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getKnowledgeBaseId(),
                chunk.getUserId(),
                document.getOriginalFileName(),
                chunk.getChunkIndex(),
                chunk.getPageNumber(),
                chunk.getParserType(),
                chunk.getOcrConfidence(),
                chunk.getContent(),
                vector.isEmpty() ? null : vector,
                chunk.getEmbeddingModel(),
                chunk.getEmbeddingDim()
        );
        try {
            elasticsearchClient.index(request -> request
                    .index(INDEX)
                    .id(String.valueOf(chunk.getId()))
                    .document(index)
                    .refresh(Refresh.True));
        } catch (IOException ex) {
            throw new IllegalStateException("Write Elasticsearch chunk index failed: " + ex.getMessage(), ex);
        }
    }

    public List<SearchResponse> search(Long userId, Long knowledgeBaseId, String keyword, int page, int size) {
        return searchChunks(userId, knowledgeBaseId, keyword, page, size).stream()
                .map(SearchResponse::from)
                .toList();
    }

    public List<ChunkSearchResult> searchChunks(Long userId, Long knowledgeBaseId, String keyword, int page, int size) {
        String queryText = keyword == null ? "" : keyword.trim();
        if (queryText.isBlank()) {
            return List.of();
        }

        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 50);
        int from = safePage * safeSize;
        int candidateSize = candidateSize(from, safeSize);

        Map<Long, Candidate> candidates = new LinkedHashMap<>();
        List<Hit<DocumentChunkIndex>> bm25Hits = fetchBm25Hits(userId, knowledgeBaseId, queryText, candidateSize);
        addBm25Candidates(candidates, bm25Hits);

        List<Double> queryVector = queryVector(queryText);
        if (!queryVector.isEmpty()) {
            enrichBm25CandidatesWithVectors(candidates, queryVector);
            addVectorCandidates(candidates, userId, knowledgeBaseId, queryVector);
        }

        double maxBm25 = candidates.values().stream()
                .map(candidate -> candidate.bm25Raw)
                .filter(score -> score != null && score > 0.0)
                .mapToDouble(Double::doubleValue)
                .max()
                .orElse(0.0);

        return candidates.values().stream()
                .map(candidate -> toResult(candidate, queryText, maxBm25))
                .sorted(Comparator.<ChunkSearchResult>comparingDouble(result -> nullToZero(result.score()))
                        .reversed()
                        .thenComparing(result -> nullToZero(result.chunkId())))
                .skip(from)
                .limit(safeSize)
                .toList();
    }

    private int candidateSize(int from, int safeSize) {
        int configured = Math.max(ragProperties.getBm25CandidateSize(), 10);
        int requested = from + safeSize * 5;
        return Math.min(Math.max(configured, requested), 200);
    }

    private List<Hit<DocumentChunkIndex>> fetchBm25Hits(
            Long userId,
            Long knowledgeBaseId,
            String keyword,
            int size
    ) {
        try {
            var response = elasticsearchClient.search(request -> request
                            .index(INDEX)
                            .from(0)
                            .size(size)
                            .query(buildQuery(userId, knowledgeBaseId, keyword)),
                    DocumentChunkIndex.class);
            return response.hits().hits();
        } catch (IOException ex) {
            log.warn("Elasticsearch BM25 search failed, vector retrieval may still continue: {}", ex.getMessage());
            return List.of();
        }
    }

    private void addBm25Candidates(Map<Long, Candidate> candidates, List<Hit<DocumentChunkIndex>> hits) {
        for (Hit<DocumentChunkIndex> hit : hits) {
            DocumentChunkIndex source = hit.source();
            if (source == null || source.getContent() == null || source.getContent().isBlank()) {
                continue;
            }
            Candidate candidate = candidates.computeIfAbsent(source.getChunkId(), id -> fromIndex(source));
            candidate.bm25Raw = hit.score();
            if (source.getEmbedding() != null && !source.getEmbedding().isEmpty()) {
                candidate.embedding = source.getEmbedding();
                candidate.embeddingModel = source.getEmbeddingModel();
                candidate.embeddingDim = source.getEmbeddingDim();
                candidate.hasEmbedding = true;
            }
        }
    }

    private void enrichBm25CandidatesWithVectors(Map<Long, Candidate> candidates, List<Double> queryVector) {
        String activeModel = embeddingClient.model();
        Set<Long> chunkIds = candidates.values().stream()
                .filter(candidate -> !EmbeddingCompatibility.usable(
                        activeModel,
                        queryVector,
                        candidate.embedding,
                        candidate.embeddingModel,
                        candidate.embeddingDim
                ))
                .map(candidate -> candidate.chunkId)
                .collect(Collectors.toSet());
        Map<Long, DocumentChunkEntity> chunkMap = selectChunksByIds(chunkIds);
        for (Candidate candidate : candidates.values()) {
            DocumentChunkEntity entity = chunkMap.get(candidate.chunkId);
            if ((candidate.embedding == null || candidate.embedding.isEmpty()) && entity != null) {
                candidate.embedding = embeddingVectorCodec.decode(entity.getEmbedding());
                candidate.embeddingModel = entity.getEmbeddingModel();
                candidate.embeddingDim = entity.getEmbeddingDim();
                candidate.hasEmbedding = !candidate.embedding.isEmpty();
            }
            updateVectorScore(candidate, queryVector);
        }
    }

    private void addVectorCandidates(
            Map<Long, Candidate> candidates,
            Long userId,
            Long knowledgeBaseId,
            List<Double> queryVector
    ) {
        int scanLimit = Math.min(Math.max(ragProperties.getVectorScanLimit(), 10), 5000);
        List<DocumentChunkEntity> chunks = documentChunkMapper.selectList(Wrappers.<DocumentChunkEntity>lambdaQuery()
                .eq(DocumentChunkEntity::getUserId, userId)
                .eq(DocumentChunkEntity::getKnowledgeBaseId, knowledgeBaseId)
                .isNotNull(DocumentChunkEntity::getEmbedding)
                .orderByDesc(DocumentChunkEntity::getCreateTime)
                .last("LIMIT " + scanLimit));
        Map<Long, DocumentEntity> documentMap = selectDocuments(chunks);
        chunks.stream()
                .map(chunk -> fromVectorChunk(chunk, documentMap, queryVector))
                .filter(candidate -> candidate.vectorScore != null && candidate.vectorScore > 0.0)
                .sorted(Comparator.<Candidate>comparingDouble(candidate -> candidate.vectorScore).reversed())
                .limit(Math.max(ragProperties.getVectorCandidateSize(), 1))
                .forEach(candidate -> mergeVectorCandidate(candidates, candidate));
    }

    private Candidate fromVectorChunk(
            DocumentChunkEntity chunk,
            Map<Long, DocumentEntity> documentMap,
            List<Double> queryVector
    ) {
        DocumentEntity document = documentMap.get(chunk.getDocumentId());
        Candidate candidate = new Candidate();
        candidate.chunkId = chunk.getId();
        candidate.documentId = chunk.getDocumentId();
        candidate.knowledgeBaseId = chunk.getKnowledgeBaseId();
        candidate.fileName = document == null ? "document-" + chunk.getDocumentId() : document.getOriginalFileName();
        candidate.chunkIndex = chunk.getChunkIndex();
        candidate.pageNumber = chunk.getPageNumber();
        candidate.parserType = chunk.getParserType();
        candidate.ocrConfidence = chunk.getOcrConfidence();
        candidate.content = chunk.getContent();
        candidate.embedding = embeddingVectorCodec.decode(chunk.getEmbedding());
        candidate.embeddingModel = chunk.getEmbeddingModel();
        candidate.embeddingDim = chunk.getEmbeddingDim();
        candidate.hasEmbedding = !candidate.embedding.isEmpty();
        updateVectorScore(candidate, queryVector);
        return candidate;
    }

    private void mergeVectorCandidate(Map<Long, Candidate> candidates, Candidate vectorCandidate) {
        Candidate existing = candidates.get(vectorCandidate.chunkId);
        if (existing == null) {
            candidates.put(vectorCandidate.chunkId, vectorCandidate);
            return;
        }
        existing.embedding = vectorCandidate.embedding;
        existing.embeddingModel = vectorCandidate.embeddingModel;
        existing.embeddingDim = vectorCandidate.embeddingDim;
        existing.hasEmbedding = vectorCandidate.hasEmbedding;
        existing.vectorScore = max(existing.vectorScore, vectorCandidate.vectorScore);
    }

    private void updateVectorScore(Candidate candidate, List<Double> queryVector) {
        if (!EmbeddingCompatibility.usable(
                embeddingClient.model(),
                queryVector,
                candidate.embedding,
                candidate.embeddingModel,
                candidate.embeddingDim
        )) {
            candidate.vectorScore = null;
            candidate.hasEmbedding = false;
            return;
        }
        candidate.vectorScore = embeddingVectorCodec.normalizedCosine(queryVector, candidate.embedding);
        candidate.hasEmbedding = true;
    }

    private ChunkSearchResult toResult(Candidate candidate, String queryText, double maxBm25) {
        double bm25Score = candidate.bm25Raw == null || maxBm25 <= 0.0 ? 0.0 : candidate.bm25Raw / maxBm25;
        Double vectorScore = candidate.vectorScore;
        double rerankScore = ragProperties.isHybridEnabled() ? ragReranker.score(queryText, candidate.content) : 0.0;
        double finalScore = ragProperties.isHybridEnabled()
                ? combine(bm25Score, vectorScore, rerankScore, candidate.bm25Raw != null)
                : bm25Score;
        return new ChunkSearchResult(
                candidate.chunkId,
                candidate.documentId,
                candidate.knowledgeBaseId,
                candidate.fileName,
                candidate.chunkIndex,
                candidate.pageNumber,
                candidate.parserType,
                candidate.ocrConfidence,
                candidate.content,
                round(finalScore),
                round(bm25Score),
                round(vectorScore),
                ragProperties.isHybridEnabled() ? round(rerankScore) : null,
                source(candidate),
                candidate.hasEmbedding,
                candidate.embeddingModel,
                candidate.embeddingDim
        );
    }

    private double combine(double bm25Score, Double vectorScore, double rerankScore, boolean hasBm25) {
        double score = 0.0;
        double weight = 0.0;
        if (hasBm25) {
            score += ragProperties.getBm25Weight() * bm25Score;
            weight += ragProperties.getBm25Weight();
        }
        if (vectorScore != null) {
            score += ragProperties.getVectorWeight() * vectorScore;
            weight += ragProperties.getVectorWeight();
        }
        score += ragProperties.getRerankWeight() * rerankScore;
        weight += ragProperties.getRerankWeight();
        if (weight <= 0.0) {
            return 0.0;
        }
        return score / weight;
    }

    private String source(Candidate candidate) {
        boolean bm25 = candidate.bm25Raw != null && candidate.bm25Raw > 0.0;
        boolean vector = candidate.vectorScore != null && candidate.vectorScore > 0.0;
        if (bm25 && vector) {
            return "HYBRID";
        }
        if (vector) {
            return "VECTOR";
        }
        return "BM25";
    }

    private Candidate fromIndex(DocumentChunkIndex source) {
        Candidate candidate = new Candidate();
        candidate.chunkId = source.getChunkId();
        candidate.documentId = source.getDocumentId();
        candidate.knowledgeBaseId = source.getKnowledgeBaseId();
        candidate.fileName = source.getFileName();
        candidate.chunkIndex = source.getChunkIndex();
        candidate.pageNumber = source.getPageNumber();
        candidate.parserType = source.getParserType();
        candidate.ocrConfidence = source.getOcrConfidence();
        candidate.content = source.getContent();
        candidate.embedding = source.getEmbedding();
        candidate.embeddingModel = source.getEmbeddingModel();
        candidate.embeddingDim = source.getEmbeddingDim();
        candidate.hasEmbedding = source.getEmbedding() != null && !source.getEmbedding().isEmpty();
        return candidate;
    }

    private List<Double> queryVector(String text) {
        if (!embeddingProperties.isEnabled() || !ragProperties.isHybridEnabled()) {
            return List.of();
        }
        try {
            List<Double> vector = embeddingClient.embed(text);
            return vector == null ? List.of() : vector;
        } catch (Exception ex) {
            log.warn("Create query embedding failed, fallback to BM25 only: {}", ex.getMessage());
            return List.of();
        }
    }

    private Map<Long, DocumentChunkEntity> selectChunksByIds(Collection<Long> chunkIds) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            return Map.of();
        }
        return documentChunkMapper.selectBatchIds(chunkIds).stream()
                .collect(Collectors.toMap(DocumentChunkEntity::getId, Function.identity(), (left, right) -> left));
    }

    private Map<Long, DocumentEntity> selectDocuments(List<DocumentChunkEntity> chunks) {
        Set<Long> documentIds = chunks.stream()
                .map(DocumentChunkEntity::getDocumentId)
                .collect(Collectors.toSet());
        if (documentIds.isEmpty()) {
            return Map.of();
        }
        return documentMapper.selectBatchIds(documentIds).stream()
                .collect(Collectors.toMap(DocumentEntity::getId, Function.identity(), (left, right) -> left));
    }

    private Query buildQuery(Long userId, Long knowledgeBaseId, String keyword) {
        return Query.of(q -> q.bool(bool -> bool
                .filter(f -> f.term(t -> t.field("userId").value(userId)))
                .filter(f -> f.term(t -> t.field("knowledgeBaseId").value(knowledgeBaseId)))
                .must(m -> m.match(match -> match.field("content").query(keyword)))));
    }

    private Double max(Double left, Double right) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return Math.max(left, right);
    }

    private Double round(Double value) {
        if (value == null) {
            return null;
        }
        return Math.round(value * 10000.0) / 10000.0;
    }

    private double nullToZero(Double value) {
        return value == null ? 0.0 : value;
    }

    private long nullToZero(Long value) {
        return value == null ? 0L : value;
    }

    private static class Candidate {
        private Long chunkId;
        private Long documentId;
        private Long knowledgeBaseId;
        private String fileName;
        private Integer chunkIndex;
        private Integer pageNumber;
        private String parserType;
        private Double ocrConfidence;
        private String content;
        private Double bm25Raw;
        private Double vectorScore;
        private List<Double> embedding;
        private boolean hasEmbedding;
        private String embeddingModel;
        private Integer embeddingDim;
    }
}
