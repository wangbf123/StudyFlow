package com.studyflow.qa;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QaRetrieveRequest(
        @NotNull Long knowledgeBaseId,
        @NotBlank @Size(max = 1000) String question
) {
}
