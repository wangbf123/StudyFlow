package com.studyflow.task;

import com.studyflow.ai.AiClient;
import com.studyflow.ai.AiResult;
import static org.junit.jupiter.api.Assertions.*;
import com.studyflow.document.*;
import com.studyflow.document.parser.DocumentTextParser;
import com.studyflow.document.parser.TextChunker;
import com.studyflow.embedding.EmbeddingClient;
import com.studyflow.embedding.EmbeddingProperties;
import com.studyflow.embedding.EmbeddingVectorCodec;
import com.studyflow.search.SearchService;
import com.studyflow.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DocumentPipelineTest {
    PipelineStore store;
    ParseTaskMapper tasks;
    DocumentMapper documents;
    DocumentChunkMapper chunks;
    AiSummaryMapper summaries;
    StorageService storage;
    EmbeddingClient embeddings;
    SearchService search;
    DocumentParseService worker;
    PipelineProperties properties;
    AiClient ai;
    ParseTaskEntity task;
    DocumentEntity document;

    @BeforeEach void setup() {
        store=mock(PipelineStore.class);tasks=mock(ParseTaskMapper.class);documents=mock(DocumentMapper.class);
        chunks=mock(DocumentChunkMapper.class);summaries=mock(AiSummaryMapper.class);storage=mock(StorageService.class);
        embeddings=mock(EmbeddingClient.class);search=mock(SearchService.class);
        properties=new PipelineProperties(); ai=mock(AiClient.class);
        when(embeddings.model()).thenReturn("test-model");
        worker=new DocumentParseService(store,properties,tasks,documents,chunks,summaries,storage,
                mock(DocumentTextParser.class),mock(TextChunker.class),ai,embeddings,
                new EmbeddingProperties(),new EmbeddingVectorCodec(new com.fasterxml.jackson.databind.ObjectMapper()),search);
        task=new ParseTaskEntity();task.setId(1L);task.setDocumentId(2L);task.setGeneration(3L);task.setStage("INDEX");task.setStatus("PROCESSING");
        document=new DocumentEntity();document.setId(2L);document.setGeneration(3L);
        when(store.claim(1L)).thenReturn(task);when(tasks.selectById(1L)).thenReturn(task);when(documents.selectById(2L)).thenReturn(document);
        DocumentChunkEntity chunk=new DocumentChunkEntity();chunk.setId(4L);chunk.setContent("text");chunk.setGeneration(3L);
        when(chunks.selectList(any())).thenReturn(List.of(chunk));
    }

    @Test void duplicateWakeupDoesNotRepeatExternalWork() {
        when(store.claim(1L)).thenReturn(null);
        worker.parse(1L);
        verifyNoInteractions(storage,embeddings,search,chunks);
    }

    @Test void staleTokenCannotDeleteReplacementWorkersCurrentGeneration() {
        when(store.checkpoint(eq(task),any())).thenReturn(false);
        worker.parse(1L);
        verify(search).indexChunks(eq(document),anyList());
        verify(search,never()).deleteDocumentGeneration(anyLong(),anyLong());
        verify(search,never()).deleteDocumentChunks(anyLong());
    }

    @Test void modelChangesRequireNewGenerationAndDoNotReuseIncompatibleCheckpoint() {
        task.setStage("EMBED");task.setEmbeddingModel("old-model");when(embeddings.model()).thenReturn("new-model");
        worker.parse(1L);
        verify(store).fail(eq(task),isA(IllegalStateException.class));
        verify(embeddings,never()).embedBatch(anyList());
        verify(store,never()).checkpoint(any(),any());
    }

    @Test void failedCleanupRetainsDatabaseChunksForRetry() {
        task.setStage("CLEANUP");doThrow(new IllegalStateException("ES unavailable")).when(search).deleteDocumentChunks(2L);
        worker.parse(1L);
        verify(store).fail(eq(task),isA(IllegalStateException.class));
        verify(chunks,never()).delete(any());
        verify(store,never()).checkpoint(any(),any());
    }

    @Test void embeddingBatchMismatchCannotAdvanceToIndex() {
        task.setStage("EMBED");when(embeddings.embedBatch(anyList())).thenReturn(List.of());
        worker.parse(1L);
        verify(store).fail(eq(task),isA(IllegalStateException.class));
        verify(store,never()).checkpoint(any(),any());
        verifyNoInteractions(search);
    }
    @Test void summaryFailureIsReportedOnItsOwnTaskWithoutIndexWork() {
        task.setStage("SUMMARY");
        when(ai.summarize(any(),anyString())).thenThrow(new IllegalStateException("model unavailable"));
        worker.parse(1L);
        verify(store).fail(eq(task),isA(IllegalStateException.class));
        verifyNoInteractions(storage,embeddings,search);
        verify(store,never()).checkpoint(any(),any());
    }

    @Test void summaryInputIsBoundedAndPublishedOnlyThroughFencedCheckpoint() {
        task.setStage("SUMMARY"); properties.setSummaryMaxChars(256);
        DocumentChunkEntity chunk=new DocumentChunkEntity();chunk.setChunkIndex(0);chunk.setContent("知".repeat(400));
        when(chunks.selectList(any())).thenReturn(List.of(chunk));
        when(ai.summarize(any(),anyString())).thenReturn(new AiResult("摘要","知识"));
        when(store.checkpoint(eq(task),any())).thenAnswer(call -> {
            java.util.function.Consumer<DocumentEntity> callback=call.getArgument(1);callback.accept(document);return true;
        });
        worker.parse(1L);
        verify(ai).summarize(any(),argThat(input -> input.length()==256));
        assertEquals(256,document.getSummaryInputChars());assertTrue(document.getSummaryTruncated());
        verify(documents).updateById(document);
    }

    @Test void retryReusesValidPersistedEmbeddingBatches() {
        task.setStage("EMBED");
        DocumentChunkEntity chunk=new DocumentChunkEntity();chunk.setChunkIndex(0);chunk.setContent("text");
        chunk.setEmbeddingModel("test-model");chunk.setEmbeddingDim(512);chunk.setEmbedding(vectorJson());
        when(chunks.selectList(any())).thenReturn(List.of(chunk));
        worker.parse(1L);
        verify(embeddings,never()).embedBatch(anyList());
        verify(store).checkpoint(eq(task),any());
    }

    @Test void embeddingBatchesHaveBoundedSizeAndIndependentCheckpoints() {
        task.setStage("EMBED");properties.setChunkBatchSize(2);
        var items=java.util.stream.IntStream.range(0,5).mapToObj(i -> {
            var chunk=new DocumentChunkEntity();chunk.setId(10L+i);chunk.setChunkIndex(i);chunk.setContent("chunk"+i);return chunk;
        }).toList();
        when(chunks.selectList(any())).thenReturn(items.subList(0,2),items.subList(2,4),items.subList(4,5));
        when(embeddings.embedBatch(anyList())).thenAnswer(call -> {
            List<String> texts=call.getArgument(0);assertTrue(texts.size()<=2);
            return texts.stream().map(text -> vector()).toList();
        });
        when(store.checkpointBatch(eq(task),any())).thenReturn(true);
        worker.parse(1L);
        verify(embeddings,times(3)).embedBatch(anyList());
        verify(store,times(3)).checkpointBatch(eq(task),any());
        verify(store).checkpoint(eq(task),any());
    }

    @Test void lostLeaseAtBatchCheckpointCannotAdvanceStage() {
        task.setStage("EMBED");when(embeddings.embedBatch(anyList())).thenReturn(List.of(vector()));
        when(store.checkpointBatch(eq(task),any())).thenReturn(false);
        worker.parse(1L);
        verify(store).fail(eq(task),isA(IllegalStateException.class));
        verify(store,never()).checkpoint(any(),any());
    }
    private List<Double> vector() {
        var values=new java.util.ArrayList<Double>(java.util.Collections.nCopies(512,0.0));values.set(0,1.0);return values;
    }
    private String vectorJson() {
        return new EmbeddingVectorCodec(new com.fasterxml.jackson.databind.ObjectMapper()).encode(vector());
    }

}
