package com.studyflow.workspace;

import com.studyflow.task.TaskResponse;
import java.util.List;

public record KnowledgeOverview(long documentCount, long readyCount, long processingCount, long failedCount,
                                long storedBytes, long chunkCount, long embeddedChunkCount, long evidenceCount,
                                long summaryReadyCount, List<FormatCount> formats, List<TaskResponse> recentTasks) {
    public record FormatCount(String type, long count) {}
}
