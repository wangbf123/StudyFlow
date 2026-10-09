package com.studyflow.search;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    private Integer pageNumber;
    private String parserType;
    private Double ocrConfidence;
    private String content;
    private List<Double> embedding;
    private String embeddingModel;
    private Integer embeddingDim;
    private Long generation;
}
