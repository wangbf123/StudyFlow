package com.studyflow.qa;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record QaEvalQuestionRequest(
        @NotBlank String question,
        List<Long> expectedChunkIds,
        List<String> expectedKeywords
) {
}
