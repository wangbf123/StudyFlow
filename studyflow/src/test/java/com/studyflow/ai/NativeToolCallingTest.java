package com.studyflow.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.studyflow.common.BusinessException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP exercises the production request factory and provider wire protocol. */
class NativeToolCallingTest {
    final ObjectMapper mapper=new ObjectMapper();HttpServer server;OpenAiCompatibleAiClient client;AiToolRequest request;
    volatile String reply="{}",captured;volatile int status=200;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{captured=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);byte[] body=reply.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(status,body.length);try(var stream=exchange.getResponseBody()){stream.write(body);}});server.start();
        var p=new AiProperties();p.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/v1");p.setApiKey("test-placeholder");p.setModel("test");
        client=new OpenAiCompatibleAiClient(p,RestClient.builder(),mapper);request=new AiToolRequest("system","context","goal","test",List.of(new AiToolRequest.Definition("read_note","read",mapper.readTree("{\"type\":\"object\"}"))));
    }
    @AfterEach void close(){server.stop(0);}
    String response(String name,String arguments)throws Exception {
        return mapper.writeValueAsString(Map.of("model","test","choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("type","function","function",Map.of("name",name,"arguments",arguments)))))),"usage",Map.of("prompt_tokens",12,"completion_tokens",4)));
    }
    @Test void usesNativeSchemaAndSingleFunctionProtocol()throws Exception {
        reply=response("read_note","{\"noteId\":\"123\"}");var result=client.callTool(request);var wire=mapper.readTree(captured);
        assertEquals("required",wire.path("tool_choice").asText());assertFalse(wire.path("parallel_tool_calls").asBoolean());assertEquals("read_note",wire.at("/tools/0/function/name").asText());
        assertEquals("123",result.arguments().path("noteId").asText());assertEquals(12,result.promptTokens());
    }
    @Test void rejectsUnknownFunctionsAndMalformedJsonArguments()throws Exception {
        for(String raw:List.of("{\"noteId\":\"1\",\"noteId\":\"2\"}","{} {}","[]")){reply=response("read_note",raw);assertThrows(BusinessException.class,()->client.callTool(request));}
        reply=response("delete_all","{}");assertThrows(BusinessException.class,()->client.callTool(request));
    }
    @Test void providerErrorDoesNotExposeItsResponseBody(){reply="private credential";status=500;assertFalse(assertThrows(BusinessException.class,()->client.callTool(request)).getMessage().contains("private"));}
}
