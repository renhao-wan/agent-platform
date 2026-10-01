package io.github.renhaowan.platform.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.gateway.message.McpProtocol;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * 以 MCP 客户端身份访问平台网关（Streamable HTTP 自环）。
 * 会话惰性建立（initialize 响应头 Mcp-Session-Id）；
 * 遇到无效会话（HTTP 400 / -32602）自动重初始化并重试一次。
 */
@Slf4j
@Service
public class McpGatewayClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 兼容保留：请优先引用 McpProtocol.HEADER_SESSION_ID */
    public static final String SESSION_HEADER = McpProtocol.HEADER_SESSION_ID;

    private final RestClient restClient;
    private final String baseUrl;
    private final String gatewayId;
    private final String apiKey;

    private volatile String sessionKey;

    public McpGatewayClient(io.github.renhaowan.platform.agent.AgentProperties properties) {
        this.baseUrl = properties.getGatewayBaseUrl();
        this.gatewayId = properties.getGatewayId();
        this.apiKey = properties.getGatewayApiKey();
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader(McpProtocol.HEADER_API_KEY, this.apiKey)
                .build();
    }

    public synchronized void ensureSession() {
        if (sessionKey != null) {
            return;
        }
        ResponseEntity<String> entity = post(null, initializeRequest());
        sessionKey = entity.getHeaders().getFirst(SESSION_HEADER);
        if (sessionKey == null) {
            throw new IllegalStateException("gateway did not return session id");
        }
        log.info("mcp gateway session established: {}", sessionKey);
    }

    public synchronized JsonNode listTools() {
        ensureSession();
        try {
            return body(post(sessionKey, rpc(McpProtocol.METHOD_TOOLS_LIST))).path("result").path("tools");
        } catch (GatewaySessionExpiredException e) {
            reset();
            ensureSession();
            return body(post(sessionKey, rpc(McpProtocol.METHOD_TOOLS_LIST))).path("result").path("tools");
        }
    }

    /** 返回工具执行文本；失败时返回含错误说明的文本（由模型决策下一步）。 */
    public synchronized String callTool(String name, String argumentsJson) {
        ensureSession();
        try {
            return extractText(post(sessionKey, toolsCallRequest(name, argumentsJson)));
        } catch (GatewaySessionExpiredException e) {
            reset();
            ensureSession();
            return extractText(post(sessionKey, toolsCallRequest(name, argumentsJson)));
        }
    }

    public synchronized void reset() {
        sessionKey = null;
    }

    private ResponseEntity<String> post(String sessionId, Map<String, Object> body) {
        RestClient.RequestBodySpec spec = restClient.post()
                .uri("/" + gatewayId + "/mcp")
                .contentType(MediaType.APPLICATION_JSON);
        if (sessionId != null) {
            spec = spec.header(SESSION_HEADER, sessionId);
        }
        try {
            return spec.body(body).retrieve().toEntity(String.class);
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 400) {
                throw new GatewaySessionExpiredException();
            }
            throw new IllegalStateException("gateway call failed: " + e.getResponseBodyAsString(), e);
        }
    }

    private Map<String, Object> initializeRequest() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("protocolVersion", "2025-06-18");
        params.put("capabilities", Map.of());
        params.put("clientInfo", Map.of("name", "agent-platform-runtime", "version", "0.1.0"));
        return rpc(McpProtocol.METHOD_INITIALIZE, params);
    }

    private Map<String, Object> toolsCallRequest(String name, String argumentsJson) {
        JsonNode arguments;
        try {
            arguments = MAPPER.readTree(argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson);
        } catch (Exception e) {
            arguments = MAPPER.createObjectNode();
        }
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", name);
        params.put("arguments", arguments);
        return rpc(McpProtocol.METHOD_TOOLS_CALL, params);
    }

    private Map<String, Object> rpc(String method) {
        return rpc(method, null);
    }

    private Map<String, Object> rpc(String method, Map<String, Object> params) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 2);
        request.put("method", method);
        if (params != null) {
            request.put("params", params);
        }
        return request;
    }

    private JsonNode body(ResponseEntity<String> entity) {
        try {
            return MAPPER.readTree(entity.getBody() == null ? "{}" : entity.getBody());
        } catch (Exception e) {
            throw new IllegalStateException("bad gateway response: " + entity.getBody(), e);
        }
    }

    private String extractText(ResponseEntity<String> entity) {
        JsonNode result = body(entity).path("result");
        if (result.path("isError").asBoolean(false)) {
            return "tool error: " + result.path("content").path(0).path("text").asText("unknown");
        }
        return result.path("content").path(0).path("text").asText("");
    }

    static final class GatewaySessionExpiredException extends RuntimeException {
    }
}
