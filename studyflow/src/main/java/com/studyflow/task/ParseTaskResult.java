package com.studyflow.task;

public record ParseTaskResult(
        Long taskId,
        String status
) {

    public boolean retrying() {
        return TaskStatus.RETRYING.equals(status);
    }
}
