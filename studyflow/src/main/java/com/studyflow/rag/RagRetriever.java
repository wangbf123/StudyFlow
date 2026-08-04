package com.studyflow.rag;

import com.studyflow.search.SearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class RagRetriever {

    private final SearchService searchService;
    private final RagProperties properties;

    public List<RetrievedChunk> retrieve(Long userId, Long knowledgeBaseId, String question) {
        int topK = Math.max(properties.getTopK(), 1);
        return retrieve(userId, knowledgeBaseId, question, topK);
    }

    public List<RetrievedChunk> retrieve(Long userId, Long knowledgeBaseId, String question, int topK) {
        int safeTopK = Math.max(topK, 1);
        return searchService.searchChunks(userId, knowledgeBaseId, question, 0, safeTopK).stream()
                .filter(item -> item.content() != null && !item.content().isBlank())
                .map(item -> new RetrievedChunk(
                        item.chunkId(),
                        item.documentId(),
                        item.knowledgeBaseId(),
                        item.fileName(),
                        item.chunkIndex(),
                        item.pageNumber(),
                        item.parserType(),
                        item.ocrConfidence(),
                        item.content(),
                        item.bm25Score(),
                        item.vectorScore(),
                        item.rerankScore(),
                        item.source(),
                        item.score()
                ))
                .toList();
    }
}
