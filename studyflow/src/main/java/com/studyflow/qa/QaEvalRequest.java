package com.studyflow.qa;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record QaEvalRequest(
        @NotNull Long knowledgeBaseId,
        @NotEmpty List<@Valid QaEvalQuestionRequest> questions
) {
}
