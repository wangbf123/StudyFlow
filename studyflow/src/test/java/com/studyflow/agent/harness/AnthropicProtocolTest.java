package com.studyflow.agent.harness;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.studyflow.agent.*;
import com.studyflow.agent.model.AnthropicAdapter;
import com.studyflow.ai.*;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class AnthropicProtocolTest {
    @Test
    void nativeMessagesToolUseAndUsageRoundTripThroughOfficialSdk() throws Exception {
        var mapper = new ObjectMapper();
        var seen = new AtomicReference<JsonNode>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/messages",
                exchange -> {
                    var request = mapper.readTree(exchange.getRequestBody());
                    seen.set(request);
                    String content =
                            request.has("tools")
                                    ? "[{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"read_note\",\"input\":{\"noteId\":\"42\"}}]"
                                    : "[{\"type\":\"text\",\"text\":\"verified\"}]";
                    byte[] body =
                            ("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-test\",\"content\":"
                                            + content
                                            + ",\"stop_reason\":\"end_turn\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":19,\"output_tokens\":7}}")
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, body.length);
                    try (var out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                });
        server.start();
        try {
            var route = new HarnessProperties.ModelRoute();
            route.setModel("claude-test");
            route.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            try (var adapter =
                    new AnthropicAdapter(route, "local-fixture-only", new AgentJson(mapper))) {
                var response =
                        adapter.chat(
                                new AiChatRequest("system", "user", "question", null, 0.0, 123));
                assertEquals("verified", response.content());
                assertEquals(19, response.promptTokens());
                assertEquals(123, seen.get().path("max_tokens").asInt());
                var call =
                        adapter.callTool(
                                new AiToolRequest(
                                        "system",
                                        "user",
                                        "question",
                                        null,
                                        List.of(
                                                new AiToolRequest.Definition(
                                                        "read_note",
                                                        "Read a note",
                                                        mapper.readTree(
                                                                "{\"type\":\"object\",\"properties\":{\"noteId\":{\"type\":\"string\"}}}"))),
                                        222));
                assertEquals("read_note", call.name());
                assertEquals("42", call.arguments().path("noteId").asText());
                assertTrue(
                        seen.get()
                                .path("tool_choice")
                                .path("disable_parallel_tool_use")
                                .asBoolean());
                assertEquals(222, seen.get().path("max_tokens").asInt());
            }
        } finally {
            server.stop(0);
        }
    }
}
