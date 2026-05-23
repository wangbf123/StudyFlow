package com.studyflow.kb;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseUpdateRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description
) {
}
