package com.studyflow.agent.harness;

import static com.studyflow.agent.AgentTypes.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.*;
import com.studyflow.agent.*;
import com.studyflow.agent.tools.*;
import com.studyflow.common.BusinessException;
import com.studyflow.notebook.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.*;

import java.net.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

class McpProtocolTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void initializesListsCallsImportsAndRejectsUnselectedScopeUsingOfficialSdk() throws Exception {
        var mapper = new ObjectMapper();
        var methods = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/mcp",
                exchange -> {
                    if (!exchange.getRequestMethod().equals("POST")) {
                        exchange.sendResponseHeaders(405, -1);
                        exchange.close();
                        return;
                    }
                    var request = mapper.readTree(exchange.getRequestBody());
                    String method = request.path("method").asText();
                    methods.add(method);
                    if (!request.has("id")) {
                        exchange.sendResponseHeaders(202, -1);
                        exchange.close();
                        return;
                    }
                    Object result =
                            switch (method) {
                                case "initialize" ->
                                        Map.of(
                                                "protocolVersion",
                                                "2025-11-25",
                                                "capabilities",
                                                Map.of(
                                                        "tools",
                                                        Map.of("listChanged", false),
                                                        "resources",
                                                        Map.of(
                                                                "subscribe",
                                                                false,
                                                                "listChanged",
                                                                false)),
                                                "serverInfo",
                                                Map.of("name", "local-fixture", "version", "1.0"));
                                case "tools/list" ->
                                        Map.of(
                                                "tools",
                                                List.of(
                                                        Map.of(
                                                                "name",
                                                                "lookup",
                                                                "description",
                                                                "Read reference data",
                                                                "inputSchema",
                                                                Map.of(
                                                                        "type",
                                                                        "object",
                                                                        "properties",
                                                                        Map.of(
                                                                                "query",
                                                                                Map.of(
                                                                                        "type",
                                                                                        "string")),
                                                                        "required",
                                                                        List.of("query"),
                                                                        "additionalProperties",
                                                                        false)),
                                                        Map.of(
                                                                "name",
                                                                "delete_all",
                                                                "description",
                                                                "Not permitted",
                                                                "inputSchema",
                                                                Map.of("type", "object"))));
                                case "tools/call" ->
                                        Map.of(
                                                "content",
                                                List.of(
                                                        Map.of(
                                                                "type",
                                                                "text",
                                                                "text",
                                                                "external reference data")),
                                                "isError",
                                                false);
                                case "resources/list" ->
                                        Map.of(
                                                "resources",
                                                List.of(
                                                        Map.of(
                                                                "uri",
                                                                "kb://fixture/source",
                                                                "name",
                                                                "Source",
                                                                "mimeType",
                                                                "text/plain")));
                                case "resources/read" ->
                                        Map.of(
                                                "contents",
                                                List.of(
                                                        Map.of(
                                                                "uri",
                                                                "kb://fixture/source",
                                                                "mimeType",
                                                                "text/plain",
                                                                "text",
                                                                "可引用的外部资料原文。")));
                                default -> Map.of();
                            };
                    byte[] body =
                            mapper.writeValueAsBytes(
                                    Map.of(
                                            "jsonrpc",
                                            "2.0",
                                            "id",
                                            request.get("id"),
                                            "result",
                                            result));
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        var config = new HarnessProperties();
        var endpoint = new HarnessProperties.McpEndpoint();
        endpoint.setEnabled(true);
        endpoint.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp");
        endpoint.setTools(List.of("lookup"));
        endpoint.setResourcePrefixes(List.of("kb://fixture/"));
        config.getMcp().put("fixture", endpoint);
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), eq(1L), eq("fixture")))
                .thenReturn(List.of("[4]"));
        var notes = mock(NoteService.class);
        when(notes.scope(eq(1L), anyList())).thenAnswer(i -> i.getArgument(1));
        var state =
                CheckpointState.initial(List.of(), new AgentProperties())
                        .withScope(List.of(4L), "research");
        var run = new Claim(10L, 2L, 1L, 4L, "查资料", "TOOL", "DECIDE", "lease", 1, state);
        try (var mcp = new McpConnections(config, jdbc, new AgentJson(mapper), notes)) {
            assertEquals(Set.of("mcp__fixture__lookup"), mcp.definitions(run).keySet());
            assertTrue(
                    mcp.call(
                                    run,
                                    "mcp__fixture__lookup",
                                    mapper.readTree("{\"query\":\"reference\"}"))
                            .contains("untrustedExternalData"));
            assertThrows(
                    BusinessException.class,
                    () -> mcp.call(run, "mcp__fixture__delete_all", mapper.readTree("{}")));
            var restricted =
                    new Claim(
                            11L,
                            2L,
                            1L,
                            5L,
                            "限制范围",
                            "TOOL",
                            "DECIDE",
                            "lease",
                            1,
                            state.withScope(List.of(5L), "research"));
            assertTrue(mcp.definitions(restricted).isEmpty());
            assertThrows(
                    BusinessException.class,
                    () -> mcp.call(restricted, "mcp__fixture__lookup", mapper.readTree("{}")));
            assertNotNull(mcp.resources(1L, "fixture"));
            mcp.importResource(1L, "fixture", "kb://fixture/source", 4L, "资料");
            var capture = org.mockito.ArgumentCaptor.forClass(NoteTypes.Write.class);
            verify(notes).save(eq(1L), isNull(), capture.capture());
            assertTrue(capture.getValue().body().contains("可引用的外部资料原文"));
            assertEquals("fixture", capture.getValue().properties().path("connector").asText());
            assertThrows(
                    BusinessException.class,
                    () -> mcp.importResource(1L, "fixture", "file:///etc/passwd", 4L, "拒绝"));
            assertTrue(
                    methods.containsAll(
                            List.of(
                                    "initialize",
                                    "tools/list",
                                    "tools/call",
                                    "resources/list",
                                    "resources/read")));
        } finally {
            server.stop(0);
        }
    }
}
