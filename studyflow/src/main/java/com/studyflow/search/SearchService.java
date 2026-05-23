package com.studyflow.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.Refresh;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.studyflow.document.DocumentChunkEntity;
import com.studyflow.document.DocumentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchService {

    private static final String INDEX = "studyflow_document_chunk";

    private final ElasticsearchClient elasticsearchClient;

    public void indexChunks(DocumentEntity document, List<DocumentChunkEntity> chunks) {
        for (DocumentChunkEntity chunk : chunks) {
            indexChunk(document, chunk);
        }
    }

    public void indexChunk(DocumentEntity document, DocumentChunkEntity chunk) {
        DocumentChunkIndex index = new DocumentChunkIndex(
                chunk.getId(),
                chunk.getDocumentId(),
                chunk.getKnowledgeBaseId(),
                chunk.getUserId(),
                document.getOriginalFileName(),
                chunk.getChunkIndex(),
                chunk.getContent()
        );
        try {
            elasticsearchClient.index(request -> request
                    .index(INDEX)
                    .id(String.valueOf(chunk.getId()))
                    .document(index)
                    .refresh(Refresh.True));
        } catch (IOException ex) {
            throw new IllegalStateException("写入 Elasticsearch 失败：" + ex.getMessage(), ex);
        }
    }

    public List<SearchResponse> search(Long userId, Long knowledgeBaseId, String keyword, int page, int size) {
        int from = Math.max(page, 0) * Math.min(Math.max(size, 1), 50);
        int safeSize = Math.min(Math.max(size, 1), 50);
        try {
            var response = elasticsearchClient.search(request -> request
                            .index(INDEX)
                            .from(from)
                            .size(safeSize)
                            .query(buildQuery(userId, knowledgeBaseId, keyword)),
                    DocumentChunkIndex.class);
            return response.hits().hits().stream()
                    .filter(hit -> hit.source() != null)
                    .map(hit -> SearchResponse.from(hit.source(), hit.score()))
                    .toList();
        } catch (IOException ex) {
            throw new IllegalStateException("Elasticsearch 查询失败：" + ex.getMessage(), ex);
        }
    }

    private Query buildQuery(Long userId, Long knowledgeBaseId, String keyword) {
        return Query.of(q -> q.bool(bool -> bool
                .filter(f -> f.term(t -> t.field("userId").value(userId)))
                .filter(f -> f.term(t -> t.field("knowledgeBaseId").value(knowledgeBaseId)))
                .must(m -> m.match(match -> match.field("content").query(keyword)))));
    }
}
