package com.studyflow.task;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.studyflow.ai.AiClient;
import com.studyflow.ai.AiResult;
import com.studyflow.document.*;
import com.studyflow.document.parser.DocumentTextParser;
import com.studyflow.document.parser.ParsedDocument;
import com.studyflow.document.parser.TextChunker;
import com.studyflow.embedding.EmbeddingClient;
import com.studyflow.embedding.EmbeddingProperties;
import com.studyflow.embedding.EmbeddingVectorCodec;
import com.studyflow.search.SearchService;
import com.studyflow.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** External calls run outside transactions; only stage results and next wakeup commit together. */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentParseService {
    private final PipelineStore store;
    private final PipelineProperties properties;
    private final ParseTaskMapper tasks;
    private final DocumentMapper documents;
    private final DocumentChunkMapper chunks;
    private final AiSummaryMapper summaries;
    private final StorageService storage;
    private final DocumentTextParser parser;
    private final TextChunker chunker;
    private final AiClient ai;
    private final EmbeddingClient embeddings;
    private final EmbeddingProperties embeddingProperties;
    private final EmbeddingVectorCodec codec;
    private final SearchService search;

    public ParseTaskResult parse(Long taskId) {
        ParseTaskEntity task = store.claim(taskId);
        if (task == null) {
            ParseTaskEntity existing = tasks.selectById(taskId);
            return new ParseTaskResult(taskId, existing == null ? TaskStatus.CANCELLED : existing.getStatus());
        }
        try (Heartbeat heartbeat = new Heartbeat(task)) {
            DocumentEntity document = documents.selectById(task.getDocumentId());
            if (document == null) throw new IllegalStateException("文档不存在");
            switch (PipelineStage.valueOf(task.getStage())) {
                case PARSE -> parseStage(task, document, heartbeat);
                case EMBED -> embedStage(task, document, heartbeat);
                case INDEX -> indexStage(task, document, heartbeat);
                case SUMMARY -> summaryStage(task, document, heartbeat);
                case CLEANUP -> cleanupStage(task, document, heartbeat);
            }
        } catch (Exception ex) {
            log.warn("Pipeline stage failed: task={},stage={},generation={}", task.getId(), task.getStage(), task.getGeneration(), ex);
            store.fail(task, ex);
        }
        ParseTaskEntity current = tasks.selectById(taskId);
        return new ParseTaskResult(taskId, current == null ? TaskStatus.CANCELLED : current.getStatus());
    }

    private void parseStage(ParseTaskEntity task, DocumentEntity document, Heartbeat heartbeat) {
        byte[] bytes = storage.getObject(document.getObjectKey());
        ParsedDocument result = parser.parseDocument(document.getOriginalFileName(), document.getFileType(), bytes);
        List<TextChunker.ParsedChunk> parsed = chunker.chunkDocument(result);
        if (parsed.isEmpty()) throw new IllegalStateException("未解析到有效文本分片");
        heartbeat.assertOwned();
        store.checkpoint(task, current -> {
            chunks.delete(Wrappers.<DocumentChunkEntity>lambdaQuery()
                    .eq(DocumentChunkEntity::getDocumentId, current.getId())
                    .eq(DocumentChunkEntity::getGeneration, task.getGeneration()));
            for (int i = 0; i < parsed.size(); i++) {
                TextChunker.ParsedChunk item = parsed.get(i);
                DocumentChunkEntity chunk = new DocumentChunkEntity();
                chunk.setDocumentId(current.getId());
                chunk.setKnowledgeBaseId(current.getKnowledgeBaseId());
                chunk.setUserId(current.getUserId());
                chunk.setGeneration(task.getGeneration());
                chunk.setChunkIndex(i);
                chunk.setContent(item.content());
                chunk.setCharCount(item.content().length());
                chunk.setPageNumber(item.pageNumber());
                chunk.setParserType(item.parserType());
                chunk.setOcrConfidence(item.ocrConfidence());
                chunks.insert(chunk);
            }
            current.setParserType(result.parserType());
            current.setPageCount(result.pageCount());
            current.setOcrPageCount(result.ocrPageCount());
            current.setOcrElapsedMs(result.ocrElapsedMs());
            documents.updateById(current);
        });
    }

    private void embedStage(ParseTaskEntity task, DocumentEntity document, Heartbeat heartbeat) {
        assertEmbeddingVersion(task);
        int cursor = -1;
        boolean found = false;
        while (true) {
            List<DocumentChunkEntity> page = chunkPage(task, cursor);
            if (page.isEmpty()) break;
            found = true;
            List<DocumentChunkEntity> missing = page.stream().filter(item -> !hasCurrentVector(item)).toList();
            if (embeddingProperties.isEnabled() && !missing.isEmpty()) {
                List<List<Double>> vectors = embeddings.embedBatch(missing.stream().map(DocumentChunkEntity::getContent).toList());
                if (vectors.size() != missing.size()) throw new IllegalStateException("Embedding count mismatch");
                for (List<Double> vector : vectors) validateVector(vector);
                heartbeat.assertOwned();
                boolean saved = store.checkpointBatch(task, current -> {
                    for (int i = 0; i < missing.size(); i++) {
                        DocumentChunkEntity item = missing.get(i);
                        item.setEmbedding(codec.encode(vectors.get(i)));
                        item.setEmbeddingModel(embeddings.model());
                        item.setEmbeddingDim(vectors.get(i).size());
                        chunks.updateById(item);
                    }
                });
                if (!saved) throw new IllegalStateException("Worker lease lost before embedding batch checkpoint");
            }
            cursor = page.getLast().getChunkIndex() == null ? 0 : page.getLast().getChunkIndex();
            if (page.size() < properties.chunkBatchSize()) break;
        }
        if (!found) throw new IllegalStateException("PARSE检查点分片不存在");
        heartbeat.assertOwned();
        store.checkpoint(task, current -> {});
    }

    private boolean hasCurrentVector(DocumentChunkEntity chunk) {
        if (!java.util.Objects.equals(embeddings.model(), chunk.getEmbeddingModel())
                || !Integer.valueOf(embeddingProperties.getDimension()).equals(chunk.getEmbeddingDim())) return false;
        try { validateVector(codec.decode(chunk.getEmbedding())); return true; }
        catch (RuntimeException invalid) { return false; }
    }

    private void validateVector(List<Double> vector) {
        if (vector == null || vector.size() != embeddingProperties.getDimension()
                || vector.stream().anyMatch(v -> v == null || !Double.isFinite(v))
                || vector.stream().allMatch(v -> v == 0.0)) {
            throw new IllegalStateException("Embedding must be finite, nonzero and match configured dimension");
        }
    }

    private void indexStage(ParseTaskEntity task, DocumentEntity document, Heartbeat heartbeat) {
        assertEmbeddingVersion(task);
        int cursor = -1;
        boolean found = false;
        while (true) {
            List<DocumentChunkEntity> page = chunkPage(task, cursor);
            if (page.isEmpty()) break;
            found = true;
            heartbeat.assertOwned();
            search.indexChunks(document, page);
            cursor = page.getLast().getChunkIndex() == null ? 0 : page.getLast().getChunkIndex();
            if (page.size() < properties.chunkBatchSize()) break;
        }
        if (!found) throw new IllegalStateException("EMBED检查点分片不存在");
        heartbeat.assertOwned();
        boolean published = store.checkpoint(task, current -> {});
        // Only retired-generation reconciliation may delete a late worker's projection.
        if (!published) log.info("Discard stale index publication: task={},generation={}", task.getId(), task.getGeneration());
    }

    private void summaryStage(ParseTaskEntity task, DocumentEntity document, Heartbeat heartbeat) {
        StringBuilder context = new StringBuilder();
        int cursor = -1;
        int limit = properties.summaryMaxChars();
        while (context.length() <= limit) {
            List<DocumentChunkEntity> page = chunkPage(task, cursor);
            if (page.isEmpty()) break;
            for (DocumentChunkEntity chunk : page) {
                if (!context.isEmpty()) context.append('\n');
                String content = chunk.getContent();
                context.append(content, 0, Math.min(content.length(), limit + 1 - context.length()));
                if (context.length() > limit) break;
            }
            cursor = page.getLast().getChunkIndex() == null ? 0 : page.getLast().getChunkIndex();
            if (page.size() < properties.chunkBatchSize()) break;
        }
        if (context.isEmpty()) throw new IllegalStateException("Summary requires parsed chunks");
        boolean truncated = context.length() > limit;
        String input = context.substring(0, Math.min(limit, context.length()));
        AiResult summary = ai.summarize(document.getOriginalFileName(), input);
        if (summary == null || summary.summary() == null || summary.summary().isBlank()) {
            throw new IllegalStateException("Summary provider returned no summary");
        }
        heartbeat.assertOwned();
        store.checkpoint(task, current -> {
            current.setSummary(summary.summary());
            current.setSummaryInputChars(input.length());
            current.setSummaryTruncated(truncated);
            documents.updateById(current);
            saveSummary(current, summary);
        });
    }

    private List<DocumentChunkEntity> chunkPage(ParseTaskEntity task, int afterIndex) {
        return chunks.selectList(Wrappers.<DocumentChunkEntity>lambdaQuery()
                .eq(DocumentChunkEntity::getDocumentId, task.getDocumentId())
                .eq(DocumentChunkEntity::getGeneration, task.getGeneration())
                .gt(DocumentChunkEntity::getChunkIndex, afterIndex)
                .orderByAsc(DocumentChunkEntity::getChunkIndex)
                .last("LIMIT " + properties.chunkBatchSize()));
    }

    private void cleanupStage(ParseTaskEntity task, DocumentEntity document, Heartbeat heartbeat) {
        // Tombstone hides results immediately; physical index deletion is retryable and idempotent.
        search.deleteDocumentChunks(document.getId());
        heartbeat.assertOwned();
        store.checkpoint(task, current -> {
            chunks.delete(Wrappers.<DocumentChunkEntity>lambdaQuery().eq(DocumentChunkEntity::getDocumentId, document.getId()));
            summaries.delete(Wrappers.<AiSummaryEntity>lambdaQuery().eq(AiSummaryEntity::getDocumentId, document.getId()));
        });
    }

    private void assertEmbeddingVersion(ParseTaskEntity task) {
        if ((task.getEmbeddingModel() != null && !task.getEmbeddingModel().equals(embeddings.model()))
                || (task.getEmbeddingDimension() != null && task.getEmbeddingDimension() != embeddingProperties.getDimension())) {
            throw new IllegalStateException("Embedding configuration changed; rebuild document to create a new generation");
        }
    }

    private void saveSummary(DocumentEntity document, AiResult result) {
        AiSummaryEntity summary = summaries.selectOne(Wrappers.<AiSummaryEntity>lambdaQuery().eq(AiSummaryEntity::getDocumentId, document.getId()));
        boolean insert = summary == null;
        if (insert) {
            summary = new AiSummaryEntity();
            summary.setDocumentId(document.getId());
            summary.setUserId(document.getUserId());
        }
        summary.setSummary(result.summary());
        summary.setKeywords(result.keywords());
        summary.setProvider("configured");
        if (insert) summaries.insert(summary); else summaries.updateById(summary);
    }

    private final class Heartbeat implements AutoCloseable {
        private final AtomicBoolean owned = new AtomicBoolean(true);
        private final ScheduledExecutorService scheduler;
        private Heartbeat(ParseTaskEntity task) {
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "pipeline-lease-" + task.getId());
                thread.setDaemon(true);
                return thread;
            });
            int interval = Math.max(1, properties.leaseSeconds() / 3);
            scheduler.scheduleAtFixedRate(() -> {
                try { if (!store.renew(task)) owned.set(false); }
                catch (Exception ex) { owned.set(false); log.warn("Lost pipeline lease: {}", task.getId()); }
            }, interval, interval, TimeUnit.SECONDS);
        }
        private void assertOwned() {
            if (!owned.get()) throw new IllegalStateException("Worker lease lost; stale result discarded");
        }
        @Override public void close() { scheduler.shutdownNow(); }
    }
}
