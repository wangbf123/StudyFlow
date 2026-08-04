package com.studyflow.ai;

public record AiStreamChunk(
        String delta,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        boolean done
) {
    public static AiStreamChunk delta(String delta, String model) {
        return new AiStreamChunk(delta, model, null, null, false);
    }

    public static AiStreamChunk done(String model, Integer promptTokens, Integer completionTokens) {
        return new AiStreamChunk("", model, promptTokens, completionTokens, true);
    }
}
