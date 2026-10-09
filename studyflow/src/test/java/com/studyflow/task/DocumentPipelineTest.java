package com.studyflow.task;

import com.studyflow.ai.AiClient;
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
    ParseTaskEntity task;
    DocumentEntity document;

    @BeforeEach void setup() {
        store=mock(PipelineStore.class);tasks=mock(ParseTaskMapper.class);documents=mock(DocumentMapper.class);
        chunks=mock(DocumentChunkMapper.class);summaries=mock(AiSummaryMapper.class);storage=mock(StorageService.class);
        embeddings=mock(EmbeddingClient.class);search=mock(SearchService.class);
        worker=new DocumentParseService(store,new PipelineProperties(),tasks,documents,chunks,summaries,storage,
                mock(DocumentTextParser.class),mock(TextChunker.class),mock(AiClient.class),embeddings,
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
}
