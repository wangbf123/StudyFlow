package com.studyflow.qa;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record QaCreateSessionRequest(
        @NotNull Long knowledgeBaseId,
        @Size(max = 120) String title
) {
}
