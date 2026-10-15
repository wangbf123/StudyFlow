package com.studyflow.mq;

import com.studyflow.task.*;
import org.junit.jupiter.api.Test;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AsyncTaskPublisherTest {
    private final DocumentParseService worker=mock(DocumentParseService.class);
    private final ParseTaskMapper tasks=mock(ParseTaskMapper.class);
    private final Executor primary=mock(Executor.class),summary=mock(Executor.class);
    private final AsyncTaskPublisher publisher=new AsyncTaskPublisher(worker,tasks,primary,summary);
    private void task(String stage) {
        var task=new ParseTaskEntity();task.setStage(stage);when(tasks.selectById(1L)).thenReturn(task);
    }
    @Test void derivedSummaryUsesAnIndependentBoundedExecutor() {
        task("SUMMARY");publisher.publishParseTask(1L);
        verify(summary).execute(any(Runnable.class));verifyNoInteractions(primary,worker);
    }
    @Test void mainStageNeverUsesSummaryWorkers() {
        task("EMBED");publisher.publishParseTask(1L);
        verify(primary).execute(any(Runnable.class));verifyNoInteractions(summary);
    }
    @Test void rejectionReachesDurableDispatcherInsteadOfSilentlyDroppingTask() {
        task("SUMMARY");doThrow(new RejectedExecutionException("full")).when(summary).execute(any());
        assertThrows(RejectedExecutionException.class,()->publisher.publishParseTask(1L));
        verifyNoInteractions(worker,primary);
    }
}
