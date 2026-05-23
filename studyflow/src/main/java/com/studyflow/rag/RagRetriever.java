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
        return searchService.search(userId, knowledgeBaseId, question, 0, topK).stream()
                .filter(item -> item.content() != null && !item.content().isBlank())
                .map(item -> new RetrievedChunk(
                        item.chunkId(),
                        item.documentId(),
                        item.knowledgeBaseId(),
                        item.fileName(),
                        item.chunkIndex(),
                        item.content(),
                        item.score()
                ))
                .toList();
    }
}
