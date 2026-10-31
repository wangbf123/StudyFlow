package com.studyflow.agent.tools;

import static com.studyflow.agent.AgentTypes.*;

import com.studyflow.agent.*;
import com.studyflow.ai.AiToolRequest.Definition;
import com.studyflow.common.BusinessException;
import com.studyflow.notebook.*;

import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

import lombok.RequiredArgsConstructor;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * Official MCP SDK; administrators configure exact endpoints and read-only allowlists, users bind
 * scopes.
 */
@Service
@RequiredArgsConstructor
public class McpConnections implements AutoCloseable {
    private final HarnessProperties config;
    private final JdbcTemplate jdbc;
    private final AgentJson json;
    private final NoteService notes;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    private record Discovery(long expiresAt, Map<String, Definition> tools) {}

    private final Map<String, Discovery> discovery = new ConcurrentHashMap<>();
    private final Map<String, Long> unavailableUntil = new ConcurrentHashMap<>();

    private HarnessProperties.McpEndpoint endpoint(String key) {
        var item = config.getMcp().get(key);
        if (item == null
                || !item.isEnabled()
                || !key.matches("[a-z0-9_-]{1,24}")
                || key.contains("__")) throw new BusinessException(403, "MCP 连接未启用");
        return item;
    }

    private synchronized McpSyncClient client(Long user, String key) {
        String id = user + ":" + key;
        if (clients.containsKey(id)) return clients.get(id);
        if (clients.size() >= 128) {
            String evict = clients.keySet().iterator().next();
            discovery.remove(evict);
            unavailableUntil.remove(evict);
            var old = clients.remove(evict);
            if (old != null) old.close();
        }
        var settings = endpoint(key);
        URI uri = URI.create(settings.getUrl());
        if (!Set.of("http", "https").contains(uri.getScheme())
                || uri.getUserInfo() != null
                || uri.getFragment() != null
                || uri.getQuery() != null
                || uri.getHost() == null) throw new BusinessException(503, "MCP 端点配置无效");
        String origin = uri.getScheme() + "://" + uri.getRawAuthority();
        var request = HttpRequest.newBuilder();
        if (!settings.getTokenEnv().isBlank()) {
            String secret = System.getenv(settings.getTokenEnv());
            if (secret == null || secret.isBlank()) throw new BusinessException(503, "MCP 凭据尚未配置");
            request.header("Authorization", "Bearer " + secret);
        }
        var transport =
                HttpClientStreamableHttpTransport.builder(origin)
                        .endpoint(uri.getRawPath().isBlank() ? "/mcp" : uri.getRawPath())
                        .requestBuilder(request)
                        .clientBuilder(
                                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER))
                        .connectTimeout(Duration.ofSeconds(5))
                        .maxResponseSize(262144)
                        .openConnectionOnStartup(false)
                        .build();
        var client =
                McpClient.sync(transport)
                        .requestTimeout(
                                Duration.ofSeconds(Math.clamp(settings.getTimeoutSeconds(), 1, 10)))
                        .initializationTimeout(Duration.ofSeconds(10))
                        .build();
        try {
            client.initialize();
            clients.put(id, client);
            return client;
        } catch (Exception ex) {
            client.close();
            throw new BusinessException(502, "MCP 初始化失败");
        }
    }

    public List<Long> binding(Long user, String key) {
        endpoint(key);
        var rows =
                jdbc.query(
                        "SELECT scope_json FROM agent_connector_binding WHERE user_id=? AND"
                                + " connector_key=? AND enabled=TRUE",
                        (rs, n) -> rs.getString(1),
                        user,
                        key);
        if (rows.isEmpty()) throw new BusinessException(403, "MCP 连接尚未授权");
        List<Long> ids = new ArrayList<>();
        for (var id : json.tree(rows.getFirst())) ids.add(id.asLong());
        notes.scope(user, ids);
        return ids;
    }

    public void bind(Long user, String key, List<Long> scope, boolean enabled) {
        endpoint(key);
        var ids = notes.scope(user, scope);
        jdbc.update(
                "INSERT INTO"
                    + " agent_connector_binding(user_id,connector_key,scope_json,enabled,update_time)"
                    + " VALUES (?,?,?,?,NOW(6)) ON DUPLICATE KEY UPDATE"
                    + " scope_json=VALUES(scope_json),enabled=VALUES(enabled),revision=revision+1,update_time=NOW(6)",
                user,
                key,
                json.write(ids),
                enabled);
        discovery.remove(user + ":" + key);
        unavailableUntil.remove(user + ":" + key);
        var old = clients.remove(user + ":" + key);
        if (old != null) old.close();
    }

    public List<Map<String, Object>> describe(Long user) {
        var result = new ArrayList<Map<String, Object>>();
        config.getMcp()
                .forEach((key, value) -> {
                    if (value.isEnabled()) {
                        var rows =
                                jdbc.query(
                                        "SELECT scope_json,enabled,revision FROM"
                                                + " agent_connector_binding WHERE user_id=? AND"
                                                + " connector_key=?",
                                        (rs, n) ->
                                                Map.<String, Object>of(
                                                        "scope",
                                                        json.tree(rs.getString(1)),
                                                        "enabled",
                                                        rs.getBoolean(2),
                                                        "revision",
                                                        rs.getInt(3)),
                                        user,
                                        key);
                        result.add(
                                Map.of(
                                        "key",
                                        key,
                                        "transport",
                                        "STREAMABLE_HTTP",
                                        "allowedTools",
                                        value.getTools(),
                                        "binding",
                                        rows.isEmpty()
                                                ? Map.of("enabled", false)
                                                : rows.getFirst()));
                    }
                });
        return result;
    }

    public Map<String, Definition> definitions(Claim run) {
        var result = new LinkedHashMap<String, Definition>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        if (unavailableUntil.size() >= 512) unavailableUntil.clear();
        for (String key : config.getMcp().keySet()) {
            if (System.nanoTime() > deadline || result.size() >= 12) break;
            String cacheKey = run.userId() + ":" + key;
            try {
                var allowed = binding(run.userId(), key);
                if (!run.state().scope(run.knowledgeBaseId()).containsAll(allowed)) continue;
                if (unavailableUntil.getOrDefault(cacheKey, 0L) > System.nanoTime()) continue;
                var snapshot = discovery.get(cacheKey);
                if (snapshot == null || snapshot.expiresAt() < System.nanoTime()) {
                    var tools = discover(run.userId(), key, deadline);
                    snapshot =
                            new Discovery(System.nanoTime() + TimeUnit.SECONDS.toNanos(30), tools);
                    discovery.put(cacheKey, snapshot);
                }
                for (var item : snapshot.tools().entrySet()) {
                    if (result.size() >= 12) break;
                    result.put(item.getKey(), item.getValue());
                }
            } catch (Exception ignored) {
                unavailableUntil.put(cacheKey, System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
            }
        }
        return result;
    }

    private Map<String, Definition> discover(Long user, String key, long deadline) {
        var output = new LinkedHashMap<String, Definition>();
        var settings = endpoint(key);
        var c = client(user, key);
        String cursor = null;
        for (int page = 0; page < 3 && System.nanoTime() < deadline; page++) {
            var response = cursor == null ? c.listTools() : c.listTools(cursor);
            for (var tool : response.tools())
                if (settings.getTools().contains(tool.name())
                        && tool.name().matches("[a-zA-Z0-9_-]{1,30}")) {
                    String name = "mcp__" + key + "__" + tool.name();
                    var schema = json.tree(json.write(tool.inputSchema()));
                    if (schema.toString().length() <= 16000)
                        output.put(
                                name,
                                new Definition(
                                        name,
                                        "External read-only data: "
                                                + AgentJson.clip(tool.description(), 400),
                                        schema));
                    if (output.size() >= 12) return Map.copyOf(output);
                }
            cursor = response.nextCursor();
            if (cursor == null) break;
        }
        return Map.copyOf(output);
    }

    public String call(Claim run, String name, com.fasterxml.jackson.databind.JsonNode args) {
        String[] parts = name.split("__", 3);
        if (parts.length != 3) throw new BusinessException(403, "无效 MCP 工具");
        var settings = endpoint(parts[1]);
        if (!settings.getTools().contains(parts[2])
                || !run.state()
                        .scope(run.knowledgeBaseId())
                        .containsAll(binding(run.userId(), parts[1])))
            throw new BusinessException(403, "MCP 工具超出授权范围");
        var response =
                client(run.userId(), parts[1])
                        .callTool(
                                new McpSchema.CallToolRequest(
                                        parts[2], json.read(args.toString(), Map.class)));
        if (!run.state().scope(run.knowledgeBaseId()).containsAll(binding(run.userId(), parts[1])))
            throw new BusinessException(403, "MCP 授权在执行期间发生变化");
        if (Boolean.TRUE.equals(response.isError())) throw new BusinessException(502, "MCP 工具返回失败");
        return json.write(
                Map.of(
                        "untrustedExternalData",
                        true,
                        "content",
                        response.content(),
                        "structuredContent",
                        response.structuredContent() == null
                                ? Map.of()
                                : response.structuredContent()));
    }

    public Object resources(Long user, String key) {
        binding(user, key);
        return client(user, key).listResources();
    }

    public NoteTypes.Note importResource(Long user, String key, String uri, Long kb, String title) {
        if (!binding(user, key).contains(kb)) throw new BusinessException(403, "目标知识库未授权给此连接");
        var settings = endpoint(key);
        if (settings.getResourcePrefixes().stream().noneMatch(uri::startsWith))
            throw new BusinessException(403, "资源 URI 不在允许范围");
        var response = client(user, key).readResource(new McpSchema.ReadResourceRequest(uri));
        StringBuilder body = new StringBuilder();
        for (var content : response.contents())
            if (content instanceof McpSchema.TextResourceContents text) {
                if (body.length() + text.text().length() > 64000)
                    throw new BusinessException(400, "资源过大，请分段导入");
                body.append(text.text()).append('\n');
            }
        if (body.isEmpty()) throw new BusinessException(400, "当前仅支持文本资源");
        return notes.save(
                user,
                null,
                new NoteTypes.Write(
                        kb,
                        title,
                        body.toString(),
                        0,
                        List.of("mcp"),
                        json.tree(
                                json.write(
                                        Map.of(
                                                "connector",
                                                key,
                                                "sourceUri",
                                                uri,
                                                "sha256",
                                                com.studyflow.rag.EvidenceAnswerValidator.sha256(
                                                        body.toString())))),
                        "NOTE"));
    }

    @jakarta.annotation.PreDestroy
    public void close() {
        clients.values().forEach(McpSyncClient::close);
    }
}
