package com.studyflow.task;

public enum PipelineStage {
    PARSE, EMBED, INDEX, CLEANUP;

    public PipelineStage next() {
        return switch (this) {
            case PARSE -> EMBED;
            case EMBED -> INDEX;
            case INDEX, CLEANUP -> null;
        };
    }
}
