package com.studyflow.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.document.*;
import com.studyflow.embedding.EmbeddingClient;
import com.studyflow.embedding.EmbeddingProperties;
import com.studyflow.embedding.EmbeddingVectorCodec;
import com.studyflow.kb.KnowledgeBaseEntity;
import com.studyflow.kb.KnowledgeBaseMapper;
import com.studyflow.rag.RagProperties;
import com.studyflow.rag.RagReranker;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;

/** Real ES protocol, tenancy, generation and idempotency tests; vectors are geometric, not quality data. */
@EnabledIfEnvironmentVariable(named = "STUDYFLOW_ES_INTEGRATION", matches = "true")
class SearchProjectionIntegrationTest {
    @Test
    void nativeKnnFusionHideForeignDeletedAndOldGenerationsAndBulkWritesAreIdempotent() throws Exception {
        String endpoint = System.getenv().getOrDefault("STUDYFLOW_ES_TEST_URL", "http://127.0.0.1:9200");
        var transport = new RestClientTransport(RestClient.builder(HttpHost.create(endpoint)).build(), new JacksonJsonpMapper());
        var client = new ElasticsearchClient(transport);
        var properties = new SearchProperties();
        properties.setIndex("studyflow_eval_java_" + UUID.randomUUID().toString().replace("-", ""));
        Map<Long, DocumentEntity> documents = new HashMap<>();
        Map<Long, DocumentChunkEntity> chunks = new HashMap<>();
        var chunkMapper = mock(DocumentChunkMapper.class);
        var documentMapper = mock(DocumentMapper.class);
        var kbMapper = mock(KnowledgeBaseMapper.class);
        var kb = new KnowledgeBaseEntity();
        kb.setId(10L); kb.setUserId(1L); kb.setStatus("ACTIVE");
        when(kbMapper.selectById(10L)).thenReturn(kb);
        when(chunkMapper.selectBatchIds(anyCollection())).thenAnswer(invocation ->
                ((Collection<?>) invocation.getArgument(0)).stream().map(chunks::get).filter(java.util.Objects::nonNull).toList());
        when(documentMapper.selectBatchIds(anyCollection())).thenAnswer(invocation ->
                ((Collection<?>) invocation.getArgument(0)).stream().map(documents::get).filter(java.util.Objects::nonNull).toList());
        var embedding = mock(EmbeddingClient.class);
        when(embedding.model()).thenReturn("geometry-test");
        when(embedding.embed(anyString())).thenReturn(axis());
        var codec = new EmbeddingVectorCodec(new ObjectMapper());
        var service = new SearchService(client, properties, new ChunkIndexManager(client, properties), kbMapper,
                chunkMapper, documentMapper, embedding, new EmbeddingProperties(), codec, new RagProperties(), new RagReranker());
        try {
            DocumentEntity current = document(101L, 1L, 10L, 2L, DocumentStatus.SUCCESS);
            DocumentEntity deleted = document(201L, 1L, 10L, 1L, DocumentStatus.DELETED);
            DocumentEntity foreign = document(301L, 2L, 10L, 1L, DocumentStatus.SUCCESS);
            DocumentEntity otherKb = document(401L, 1L, 11L, 1L, DocumentStatus.SUCCESS);
            for (var document : List.of(current, deleted, foreign, otherKb)) documents.put(document.getId(), document);
            var valid = chunk(111L, current, 2L, codec);
            var stale = chunk(112L, current, 1L, codec);
            var removed = chunk(211L, deleted, 1L, codec);
            var foreignChunk = chunk(311L, foreign, 1L, codec);
            var kbChunk = chunk(411L, otherKb, 1L, codec);
            for (var chunk : List.of(valid, stale, removed, foreignChunk, kbChunk)) chunks.put(chunk.getId(), chunk);
            for (var chunk : chunks.values()) service.indexChunk(documents.get(chunk.getDocumentId()), chunk);
            service.indexChunk(current, valid);
            assertEquals(5, client.count(c -> c.index(properties.getIndex())).count(), "retry must overwrite a stable ID");
            for (var mode : List.of(SearchProperties.RetrievalMode.BM25, SearchProperties.RetrievalMode.VECTOR,
                    SearchProperties.RetrievalMode.RRF, SearchProperties.RetrievalMode.RRF_LEXICAL)) {
                var results = service.searchChunks(1L, 10L, "缓存重建", 0, 5, mode);
                assertEquals(List.of(111L), results.stream().map(ChunkSearchResult::chunkId).toList(), mode.name());
            }
            current.setParseStatus(DocumentStatus.PENDING);
            assertTrue(service.searchChunks(1L, 10L, "缓存重建", 0, 5).isEmpty(), "rebuild hides incomplete generation");
            current.setParseStatus(DocumentStatus.SUCCESS);
            kb.setStatus("DELETED");
            assertTrue(service.searchChunks(1L, 10L, "缓存重建", 0, 5).isEmpty(), "KB deletion hides stale ES projection");
            kb.setStatus("ACTIVE");
            service.deleteDocumentGeneration(101L, 1L);
            assertEquals(4, client.count(c -> c.index(properties.getIndex())).count());
            assertEquals(List.of(111L), service.searchChunks(1L, 10L, "缓存重建", 0, 5).stream()
                    .map(ChunkSearchResult::chunkId).toList(), "old generation cleanup preserves current version");
            service.deleteDocumentChunks(101L);
            assertTrue(service.searchChunks(1L, 10L, "缓存重建", 0, 5).isEmpty());
        } finally {
            if (client.indices().exists(r -> r.index(properties.getIndex())).value())
                client.indices().delete(r -> r.index(properties.getIndex()));
            transport.close();
        }
    }

    private static List<Double> axis() {
        var vector = new ArrayList<Double>(java.util.Collections.nCopies(512, 0.0));
        vector.set(0, 1.0);
        return vector;
    }

    private static DocumentEntity document(Long id, Long user, Long kb, Long generation, String status) {
        var document = new DocumentEntity();
        document.setId(id); document.setUserId(user); document.setKnowledgeBaseId(kb);
        document.setGeneration(generation); document.setOriginalFileName("note.txt"); document.setParseStatus(status);
        return document;
    }

    private static DocumentChunkEntity chunk(Long id, DocumentEntity document, Long generation, EmbeddingVectorCodec codec) {
        var chunk = new DocumentChunkEntity();
        chunk.setId(id); chunk.setDocumentId(document.getId()); chunk.setUserId(document.getUserId());
        chunk.setKnowledgeBaseId(document.getKnowledgeBaseId()); chunk.setGeneration(generation);
        chunk.setChunkIndex(0); chunk.setContent("缓存重建采用互斥锁，避免热点失效时大量请求回源。");
        chunk.setEmbedding(codec.encode(axis())); chunk.setEmbeddingDim(512); chunk.setEmbeddingModel("geometry-test");
        return chunk;
    }
}
