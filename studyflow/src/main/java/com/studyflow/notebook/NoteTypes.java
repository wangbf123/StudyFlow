package com.studyflow.notebook;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.List;

public final class NoteTypes {
    private NoteTypes() {}
    public record Write(@NotNull Long knowledgeBaseId,@NotBlank @Size(max=160) String title,
                        @NotNull @Size(max=200000) String body,@NotNull @Min(0) Integer revision,
                        @Size(max=20) List<@NotBlank @Size(max=40) String> tags,
                        JsonNode properties,@Pattern(regexp="NOTE|CONCEPT|PROJECT|DECISION|JOURNAL|REPORT") String kind) {}
    public record Summary(Long id,Long knowledgeBaseId,String title,String kind,int revision,List<String> tags,LocalDateTime updateTime) {}
    public record Link(int ordinal,Long targetId,String targetText,String label,String anchor,String currentTitle,Long knowledgeBaseId) {}
    public record Note(Long id,Long knowledgeBaseId,String title,String body,String kind,int revision,List<String> tags,
                       JsonNode properties,Long originRunId,LocalDateTime updateTime,List<Link> links) {}
    public record Page(List<Summary> items,Long nextCursor,boolean hasMore) {}
    public record Revision(int revision,LocalDateTime createTime) {}
    public record GraphNode(String id,String title,Long knowledgeBaseId,String kind,List<String> tags) {}
    public record GraphEdge(String id,String source,String target,String type) {}
    public record Graph(List<GraphNode> nodes,List<GraphEdge> edges,int totalNotes,boolean truncated,String engine) {}
}
