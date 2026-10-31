package com.studyflow.kb;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record KnowledgeBaseUpdateRequest(
        @NotBlank @Size(max = 80) String name,
        @Size(max = 500) String description,
        Long parentId,
        @Size(max = 40) String templateKey,
        @jakarta.validation.constraints.Pattern(regexp="#[0-9a-fA-F]{6}") String color
) {
    public KnowledgeBaseUpdateRequest(String name,String description) { this(name,description,null,null,null); }
}
