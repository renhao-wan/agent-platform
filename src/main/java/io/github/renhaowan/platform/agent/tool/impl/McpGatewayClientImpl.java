package io.github.renhaowan.platform.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.agent.tool.McpGatewayClient;
import io.github.renhaowan.platform.gateway.message.McpProtocol;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** McpGatewayClient 接口实现。 */
@Slf4j
@Service
public class McpGatewayClientImpl implements McpGatewayClient {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestClient restClient;
    private final String baseUrl;
    private final String gatewayId;
    private final String apiKey;

    private volatile String sessionKey;

    public McpGatewayClientImpl(io.github.renhaowan.platform.agent.AgentProperties properties) {
        this.baseUrl = properties.getGatewayBaseUrl();
        this.gatewayId = properties.getGatewayId();
        this.apiKey = properties.getGatewayApiKey();
        this.restClient = RestClient.builder()
                .baseUrl(this.baseUrl)
                .defaultHeader(McpProtocol.HEADER_API_KEY, this.apiKey)
                .build();
    }

    @Override
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

    @Override
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

    @Override
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

    @Override
    public synchronized void reset() {
        sessionKey = null;
    }

    /**
     * 统一 POST 出口（Streamable HTTP 传输：一条 /{gw}/mcp 通道走完所有方法）。
     * <p>【白话 RestClient】Spring 6 的同步 HTTP 客户端，链式拼请求。
     * retrieve().toEntity() 遇 4xx/5xx 会抛 RestClientResponseException（转成异常流）；
     * 本项目网关侧自环消息体固定 2xx，只有"会话无效"统一映射 400——按此翻译成会话重握手信号。
     */
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
