package io.github.renhaowan.platform.gateway.transport;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.gateway.message.JsonRpcRequest;
import io.github.renhaowan.platform.gateway.message.JsonRpcResponse;
import io.github.renhaowan.platform.gateway.message.MessageContext;
import io.github.renhaowan.platform.gateway.message.MessageDispatcher;
import io.github.renhaowan.platform.gateway.security.TenantContext;
import io.github.renhaowan.platform.gateway.session.GatewaySession;
import io.github.renhaowan.platform.gateway.session.GatewaySessionService;
import io.github.renhaowan.platform.gateway.session.SseConnectionRegistry;
import io.github.renhaowan.platform.gateway.session.SseHeartbeat;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * MCP Streamable HTTP 传输端点。
 * POST   /{gatewayId}/mcp   JSON-RPC 消息；initialize 创建会话并在响应头返回 Mcp-Session-Id；
 *                            客户端已开 GET 流时响应经流回，否则直接返回 JSON
 * GET    /{gatewayId}/mcp   服务器→客户端事件流（可选能力，按会话绑定）
 * DELETE /{gatewayId}/mcp   终止会话
 */
@RestController
public class StreamableGatewayController {

    public static final String SESSION_HEADER = "Mcp-Session-Id";

    private final GatewaySessionService sessionService;
    private final SseConnectionRegistry registry;
    private final SseHeartbeat heartbeat;
    private final MessageDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public StreamableGatewayController(GatewaySessionService sessionService,
                                       SseConnectionRegistry registry,
                                       SseHeartbeat heartbeat,
                                       MessageDispatcher dispatcher,
                                       ObjectMapper objectMapper) {
        this.sessionService = sessionService;
        this.registry = registry;
        this.heartbeat = heartbeat;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/{gatewayId}/mcp")
    public ResponseEntity<String> post(@PathVariable String gatewayId,
                                       @RequestHeader(value = SESSION_HEADER, required = false) String sessionHeader,
                                       @RequestBody JsonRpcRequest request) throws Exception {
        TenantContext.TenantInfo tenant = TenantContext.get();
        boolean isInitialize = "initialize".equals(request.method());

        String sessionKey;
        if (isInitialize && (sessionHeader == null || sessionHeader.isBlank())) {
            GatewaySessionService.SessionState state =
                    sessionService.create(gatewayId, GatewaySession.TRANSPORT_STREAMABLE, tenant.id());
            sessionKey = state.sessionKey();
        } else {
            if (sessionHeader == null || sessionHeader.isBlank()) {
                return invalidSession();
            }
            sessionKey = sessionHeader;
            if (!validateSession(sessionKey, gatewayId, tenant)) {
                return invalidSession();
            }
        }

        JsonRpcResponse rpcResponse = dispatcher.dispatch(request,
                new MessageContext(gatewayId, sessionKey, tenant.id(), tenant.apiKey()));
        if (rpcResponse == null) {
            return ResponseEntity.accepted().build();
        }
        String json = objectMapper.writeValueAsString(rpcResponse.toEventPayload());
        if (registry.isLocal(sessionKey)) {
            registry.send(sessionKey, json);
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok()
                .header(SESSION_HEADER, sessionKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json);
    }

    @GetMapping(path = "/{gatewayId}/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable String gatewayId,
                             @RequestHeader(SESSION_HEADER) String sessionKey,
                             HttpServletResponse response) throws Exception {
        TenantContext.TenantInfo tenant = TenantContext.get();
        if (!validateSession(sessionKey, gatewayId, tenant)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "invalid or unknown session");
            return null;
        }
        SseEmitter emitter = registry.register(sessionKey);
        heartbeat.start(emitter);
        return emitter;
    }

    @DeleteMapping("/{gatewayId}/mcp")
    public ResponseEntity<Void> terminate(@PathVariable String gatewayId,
                                          @RequestHeader(SESSION_HEADER) String sessionKey) {
        TenantContext.TenantInfo tenant = TenantContext.get();
        if (!validateSession(sessionKey, gatewayId, tenant)) {
            return ResponseEntity.notFound().build();
        }
        sessionService.close(sessionKey);
        registry.complete(sessionKey);
        return ResponseEntity.noContent().build();
    }

    private boolean validateSession(String sessionKey, String gatewayId, TenantContext.TenantInfo tenant) {
        Optional<GatewaySessionService.SessionState> state = sessionService.validate(sessionKey);
        return state.isPresent()
                && gatewayId.equals(state.get().gatewayId())
                && tenant != null && tenant.id().equals(state.get().tenantId());
    }

    private ResponseEntity<String> invalidSession() {
        JsonRpcResponse error = JsonRpcResponse.failure(null, -32602, "invalid or unknown session");
        String json;
        try {
            json = objectMapper.writeValueAsString(error.toEventPayload());
        } catch (Exception e) {
            json = "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32602,"
                    + "\"message\":\"invalid or unknown session\"}}";
        }
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_JSON).body(json);
    }
}
