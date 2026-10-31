package com.studyflow.agent.tools;

import static com.studyflow.agent.AgentTypes.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.studyflow.agent.*;
import com.studyflow.ai.AiToolRequest.Definition;
import com.studyflow.kb.KnowledgeBaseService;
import com.studyflow.rag.*;
import com.studyflow.workspace.WorkspaceService;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** Capability boundary: every tool is read-only and scope is supplied by the authenticated run. */
@Component
@RequiredArgsConstructor
public class KnowledgeTools {
    private final KnowledgeBaseService knowledgeBases;
    private final WorkspaceService workspace;
    private final RagRetriever retriever;
    private final JdbcTemplate jdbc;
    private final AgentJson json;
    private final AgentProperties properties;
    private final com.studyflow.notebook.NoteService notes;

    public record Output(String content, List<Source> sources) {}

    public List<Definition> definitions() {
        return List.of(
                definition("knowledge_overview", "统计当前知识库资料范围和处理状态；无事实证据时先了解范围。", "{}", "[]"),
                definition(
                        "list_documents",
                        "分页列出当前知识库文档。nextCursor 表示还有下一页；summary 仅供导航，不能作为事实证据。",
                        """
                        {"knowledgeBaseId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},"keyword":{"type":"string","maxLength":200},"beforeId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},"size":{"type":"integer","minimum":1,"maximum":10}}
                        """,
                        "[]"),
                definition(
                        "search_knowledge",
                        "混合检索当前知识库。用不同主题的短查询补充证据，返回原文与来源。",
                        """
                        {"query":{"type":"string","minLength":1,"maxLength":2000},"topK":{"type":"integer","minimum":1,"maximum":6}}
                        """,
                        "[\"query\"]"),
                definition(
                        "read_document",
                        "分页阅读已知文档的原文。只可读取当前知识库文档；afterChunkIndex 从返回的 nextCursor 继续。",
                        """
                        {"documentId":{"type":"string","pattern":"^[1-9][0-9]{0,18}$"},"afterChunkIndex":{"type":"integer","minimum":-1,"maximum":1000000},"maxChunks":{"type":"integer","minimum":1,"maximum":6}}
                        """,
                        "[\"documentId\"]"),
                definition(
                        "search_notes",
                        "搜索本次选定知识库的笔记；query 可为空以浏览最近笔记。返回稳定的笔记 ID 和知识库归属。",
                        "{\"query\":{\"type\":\"string\",\"maxLength\":200},\"size\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":8}}",
                        "[]"),
                definition(
                        "read_note",
                        "阅读一篇已知笔记，并返回当前版本的原文证据与双向链接。",
                        "{\"noteId\":{\"type\":\"string\",\"pattern\":\"^[1-9][0-9]{0,18}$\"}}",
                        "[\"noteId\"]"),
                definition(
                        "explore_graph",
                        "查看选定知识库内的双向链接关系；可围绕已知笔记最多探索两跳。图关系用于导航，事实仍需 read_note 原文。",
                        "{\"noteId\":{\"type\":\"string\",\"pattern\":\"^[1-9][0-9]{0,18}$\"}}",
                        "[]"),
                definition("finish_research", "证据已经充分，或没有更多资料可查时结束研究；服务端随后生成并核验报告。", "{}", "[]"));
    }

    private Definition definition(String name, String description, String fields, String required) {
        return new Definition(
                name,
                description,
                json.tree(
                        "{\"type\":\"object\",\"properties\":"
                                + fields
                                + ",\"required\":"
                                + required
                                + ",\"additionalProperties\":false}"));
    }

    public void validate(String name, JsonNode arguments) {
        var definition =
                definitions().stream()
                        .filter(t -> t.name().equals(name))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("工具不在白名单中"));
        if (arguments == null || !arguments.isObject())
            throw new IllegalArgumentException("工具参数必须是对象");
        var fields = definition.parameters().path("properties");
        for (var required : definition.parameters().path("required"))
            if (!arguments.has(required.asText()))
                throw new IllegalArgumentException("缺少工具参数：" + required.asText());
        var names = arguments.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            var rule = fields.get(field);
            var value = arguments.get(field);
            if (rule == null) throw new IllegalArgumentException("工具参数不在 schema 中：" + field);
            if ("integer".equals(rule.path("type").asText())) {
                if (!value.isIntegralNumber()
                        || !value.canConvertToInt()
                        || value.asInt() < rule.path("minimum").asInt()
                        || value.asInt() > rule.path("maximum").asInt())
                    throw new IllegalArgumentException("整数参数超出范围：" + field);
            } else {
                if (!value.isTextual()
                        || value.asText().length() < rule.path("minLength").asInt(0)
                        || value.asText().length() > rule.path("maxLength").asInt(2000)
                        || (rule.has("pattern")
                                && !value.asText().matches(rule.path("pattern").asText())))
                    throw new IllegalArgumentException("文本参数不符合 schema：" + field);
                if (field.endsWith("Id")) positiveId(value.asText());
            }
        }
        if ("search_knowledge".equals(name) && arguments.path("query").asText().isBlank())
            throw new IllegalArgumentException("检索问题不能为空");
    }

    public Output execute(Claim run, PendingCall call) {
        validate(call.tool(), call.arguments());
        for (Long kb : run.state().scope(run.knowledgeBaseId()))
            knowledgeBases.getOwnedActive(run.userId(), kb);
        var args = call.arguments();
        return switch (call.tool()) {
            case "knowledge_overview" ->
                    new Output(
                            json.write(
                                    run.state().scope(run.knowledgeBaseId()).stream()
                                            .map(id ->
                                                    Map.of(
                                                            "knowledgeBaseId",
                                                            id,
                                                            "documents",
                                                            workspace.overview(run.userId(), id),
                                                            "notes",
                                                            jdbc.queryForObject(
                                                                    "SELECT COUNT(*) FROM"
                                                                        + " knowledge_note WHERE"
                                                                        + " user_id=? AND"
                                                                        + " knowledge_base_id=? AND"
                                                                        + " status='ACTIVE'",
                                                                    Long.class,
                                                                    run.userId(),
                                                                    id)))
                                            .toList()),
                            List.of());
            case "search_notes" -> {
                var page =
                        notes.list(
                                run.userId(),
                                run.state().scope(run.knowledgeBaseId()),
                                null,
                                args.path("size").asInt(5),
                                args.path("query").asText(""));
                yield new Output(json.write(page), List.of());
            }
            case "read_note" -> {
                var note = notes.get(run.userId(), positiveId(args.path("noteId").asText()));
                if (!run.state().scope(run.knowledgeBaseId()).contains(note.knowledgeBaseId()))
                    throw new IllegalArgumentException("笔记不在本次任务范围中");
                String excerpt =
                        AgentJson.clip(
                                note.body(), Math.min(5000, properties.resultChars() - 1000));
                var chunk =
                        new RetrievedChunk(
                                note.id(),
                                note.id(),
                                note.knowledgeBaseId(),
                                note.title(),
                                0,
                                excerpt,
                                null,
                                null,
                                null,
                                "NOTE",
                                null);
                var source =
                        new Source(
                                chunk,
                                (long) note.revision(),
                                com.studyflow.rag.EvidenceAnswerValidator.sha256(note.body()));
                yield new Output(
                        json.write(
                                Map.of(
                                        "noteId",
                                        note.id(),
                                        "title",
                                        note.title(),
                                        "revision",
                                        note.revision(),
                                        "excerpt",
                                        excerpt,
                                        "links",
                                        note.links().stream().limit(12).toList(),
                                        "truncated",
                                        excerpt.length() < note.body().length())),
                        List.of(source));
            }
            case "explore_graph" -> {
                var graph =
                        notes.graph(
                                run.userId(),
                                run.state().scope(run.knowledgeBaseId()),
                                args.has("noteId")
                                        ? positiveId(args.path("noteId").asText())
                                        : null);
                yield new Output(
                        json.write(
                                Map.of(
                                        "nodes",
                                        graph.nodes().stream().limit(40).toList(),
                                        "edges",
                                        graph.edges().stream().limit(60).toList(),
                                        "bounded",
                                        true)),
                        List.of());
            }
            case "list_documents" -> {
                Long selected =
                        args.has("knowledgeBaseId")
                                ? positiveId(args.path("knowledgeBaseId").asText())
                                : run.knowledgeBaseId();
                if (!run.state().scope(run.knowledgeBaseId()).contains(selected))
                    throw new IllegalArgumentException("知识库不在本次任务范围中");
                var page =
                        workspace.documents(
                                run.userId(),
                                selected,
                                args.has("beforeId")
                                        ? positiveId(args.get("beforeId").asText())
                                        : null,
                                args.path("size").asInt(8),
                                args.path("keyword").asText(""),
                                null);
                List<Map<String, Object>> documents =
                        page.items().stream()
                                .map(doc -> {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put("documentId", doc.id().toString());
                                    row.put("name", doc.originalFileName());
                                    row.put("status", doc.parseStatus());
                                    row.put("summary", AgentJson.clip(doc.summary(), 300));
                                    return row;
                                })
                                .toList();
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("documents", documents);
                result.put("nextCursor", page.nextCursor());
                result.put("hasMore", page.hasMore());
                yield new Output(json.write(result), List.of());
            }
            case "search_knowledge" -> {
                var groups =
                        run.state().scope(run.knowledgeBaseId()).stream()
                                .map(kb ->
                                        retriever.retrieve(
                                                run.userId(),
                                                kb,
                                                args.path("query").asText(),
                                                args.path("topK").asInt(5)))
                                .toList();
                var selected = new ArrayList<RetrievedChunk>();
                for (int i = 0; i < 6; i++)
                    for (var group : groups)
                        if (i < group.size() && selected.size() < 8) selected.add(group.get(i));
                yield evidence(selected, run, null);
            }
            case "read_document" -> {
                long documentId = positiveId(args.path("documentId").asText());
                var docScopes =
                        jdbc.query(
                                "SELECT knowledge_base_id FROM document WHERE id=? AND user_id=?",
                                (rs, n) -> rs.getLong(1),
                                documentId,
                                run.userId());
                if (docScopes.isEmpty()
                        || !run.state().scope(run.knowledgeBaseId()).contains(docScopes.getFirst()))
                    throw new IllegalArgumentException("文档不在本次任务范围中");
                Long documentKb = docScopes.getFirst();
                int size =
                        Math.min(
                                args.path("maxChunks").asInt(4),
                                Math.clamp(properties.getMaxReadChunks(), 1, 6));
                var chunks =
                        jdbc.query(
                                """
                                SELECT c.*,d.original_file_name FROM document_chunk c JOIN document d ON c.document_id=d.id AND c.generation=d.generation
                                WHERE d.id=? AND d.user_id=? AND d.knowledge_base_id=? AND d.parse_status='SUCCESS'
                                  AND c.user_id=? AND c.knowledge_base_id=? AND c.chunk_index>?
                                ORDER BY c.chunk_index LIMIT ?
                                """,
                                (rs, n) ->
                                        new RetrievedChunk(
                                                rs.getLong("id"),
                                                documentId,
                                                documentKb,
                                                rs.getString("original_file_name"),
                                                rs.getInt("chunk_index"),
                                                rs.getString("content"),
                                                null,
                                                null,
                                                null,
                                                "DOCUMENT",
                                                null),
                                documentId,
                                run.userId(),
                                documentKb,
                                run.userId(),
                                documentKb,
                                args.path("afterChunkIndex").asInt(-1),
                                size + 1);
                yield evidence(
                        chunks.stream().limit(size).toList(),
                        run,
                        chunks.size() > size ? chunks.get(size - 1).chunkIndex() : null);
            }
            default -> throw new IllegalArgumentException("该动作不能作为资料工具执行");
        };
    }

    private Output evidence(List<RetrievedChunk> chunks, Claim run, Integer nextCursor) {
        List<Source> sources = new ArrayList<>();
        List<Map<String, Object>> items = new ArrayList<>();
        int chars = 0;
        for (var chunk : chunks) {
            if (chunk.content() == null || chunk.content().length() > 16000) continue;
            var generations =
                    jdbc.query(
                            """
                            SELECT c.generation FROM document_chunk c JOIN document d ON d.id=c.document_id AND d.generation=c.generation
                            WHERE c.id=? AND c.document_id=? AND c.user_id=? AND c.knowledge_base_id=?
                              AND d.user_id=? AND d.knowledge_base_id=? AND d.parse_status='SUCCESS' AND BINARY c.content=BINARY ?
                            """,
                            (rs, n) -> rs.getLong(1),
                            chunk.chunkId(),
                            chunk.documentId(),
                            run.userId(),
                            chunk.knowledgeBaseId(),
                            run.userId(),
                            chunk.knowledgeBaseId(),
                            chunk.content());
            if (generations.isEmpty()) continue;
            String excerpt = AgentJson.clip(chunk.content(), 2400);
            if (chars + excerpt.length() > properties.resultChars() - 800) break;
            chars += excerpt.length();
            sources.add(
                    new Source(
                            chunk,
                            generations.getFirst(),
                            EvidenceAnswerValidator.sha256(chunk.content())));
            items.add(
                    Map.of(
                            "documentId",
                            chunk.documentId().toString(),
                            "chunkId",
                            chunk.chunkId().toString(),
                            "name",
                            chunk.fileName(),
                            "chunkIndex",
                            chunk.chunkIndex(),
                            "excerpt",
                            excerpt));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matches", items);
        result.put("matchCount", items.size());
        result.put("nextCursor", nextCursor);
        result.put("bounded", true);
        return new Output(json.write(result), List.copyOf(sources));
    }

    private long positiveId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id > 0) return id;
        } catch (NumberFormatException ignored) {
        }
        throw new IllegalArgumentException("无效的文档或游标 ID");
    }
}
