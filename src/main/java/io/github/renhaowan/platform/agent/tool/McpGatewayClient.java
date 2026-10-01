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

    /**
     * 惰性建立 MCP 会话（首次用到才握手，之后复用）。
     * <p>MCP 握手 = 发 initialize 请求（带协议版本/能力声明），网关校验通过后
     * 在响应头 Mcp-Session-Id 返回会话凭证——类似 HTTP 会话 cookie 的获取动作。
     */
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

    /**
     * tools/list：拉取本租户可用的工具清单（名称/描述/参数 schema）。
     * 供 DynamicToolRegistry 转成 Spring AI ToolCallback——模型靠这份 schema 知道"有什么工具、怎么传参"。
     * 会话过期（网关返回 400）时自动重握手并重试一次。
     */
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

    /**
     * tools/call：执行工具并返回文本结果；失败也返回"含错误说明的文本"而非抛异常——
     * 让模型看到错误后自行决策下一步（重试/换路/放弃），这是 Agent 容错的关键设计。
     * 会话过期时自动重握手并重试一次。
     */
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
