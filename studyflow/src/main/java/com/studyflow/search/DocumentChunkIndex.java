package com.studyflow.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocumentChunkIndex {

    private Long chunkId;
    private Long documentId;
    private Long knowledgeBaseId;
    private Long userId;
    private String fileName;
    private Integer chunkIndex;
    private String content;
}
