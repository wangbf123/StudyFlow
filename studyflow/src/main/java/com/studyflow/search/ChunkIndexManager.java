package com.studyflow.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class ChunkIndexManager {
    public static final int DIMENSION = 512;
    private final ElasticsearchClient client;
    private final SearchProperties properties;
    private volatile boolean ready;

    /** Lazy setup allows application startup while Elasticsearch is being provisioned. */
    public synchronized void ensureIndex() throws IOException {
        if (ready) return;
        if (!client.indices().exists(r -> r.index(properties.getIndex())).value()) {
            try (var mapping = new ClassPathResource("search/chunk-index.json").getInputStream()) {
                try {
                    client.indices().create(r -> r.index(properties.getIndex()).withJson(mapping));
                } catch (ElasticsearchException ex) {
                    if (!"resource_already_exists_exception".equals(ex.error().type())) throw ex;
                }
            }
        }
        var metadata = client.indices().getMapping(r -> r.index(properties.getIndex()))
                .result().get(properties.getIndex());
        if (metadata == null || metadata.mappings() == null) {
            throw new IllegalStateException("Missing chunk index mapping: " + properties.getIndex());
        }
        var vector = metadata.mappings().properties().get("embedding");
        var text = metadata.mappings().properties().get("content");
        if (vector == null || !vector.isDenseVector()
                || !Integer.valueOf(DIMENSION).equals(vector.denseVector().dims())
                || !Boolean.TRUE.equals(vector.denseVector().index())
                || text == null || !text.isText() || !"cjk".equals(text.text().analyzer())) {
            throw new IllegalStateException("Incompatible chunk mapping; use a new index and rebuild: "
                    + properties.getIndex());
        }
        ready = true;
    }
}
