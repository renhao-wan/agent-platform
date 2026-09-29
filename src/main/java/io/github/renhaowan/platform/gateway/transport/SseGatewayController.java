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
import io.github.renhaowan.platform.gateway.support.JsonRpcErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * MCP SSE 传输端点。
 * GET  /{gatewayId}/mcp/sse            建连，首个事件 endpoint 告知消息端点
 * POST /{gatewayId}/mcp/message        消息入口，响应经 SSE 流回（协议规定）
 */
@RestController
public class SseGatewayController {

    private static final Logger log = LoggerFactory.getLogger(SseGatewayController.class);

    private final GatewaySessionService sessionService;
    private final SseConnectionRegistry registry;
    private final SseHeartbeat heartbeat;
    private final MessageDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public SseGatewayController(GatewaySessionService sessionService,
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

    @org.springframework.web.bind.annotation.GetMapping(path = "/{gatewayId}/mcp/sse",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@PathVariable String gatewayId, HttpServletResponse response) {
        TenantContext.TenantInfo tenant = TenantContext.get();
        response.setHeader("X-Accel-Buffering", "no");

        GatewaySessionService.SessionState state =
                sessionService.create(gatewayId, GatewaySession.TRANSPORT_SSE, tenant.id());
        SseEmitter emitter = registry.register(state.sessionKey());
        try {
            emitter.send(SseEmitter.event().name("endpoint")
                    .data("/" + gatewayId + "/mcp/message?sessionId=" + state.sessionKey()));
        } catch (Exception e) {
            log.warn("endpoint event send failed: {}", e.getMessage());
        }
        heartbeat.start(emitter);
        return emitter;
    }

    @PostMapping("/{gatewayId}/mcp/message")
    public void message(@PathVariable String gatewayId,
                        @RequestParam String sessionId,
                        @RequestBody JsonRpcRequest request,
                        HttpServletRequest httpRequest,
                        HttpServletResponse response) throws Exception {
        TenantContext.TenantInfo tenant = TenantContext.get();
        Optional<GatewaySessionService.SessionState> state = sessionService.validate(sessionId);

        // 会话不存在、网关不匹配或租户不匹配（会话劫持防护）一律按无效会话拒绝
        boolean valid = state.isPresent()
                && gatewayId.equals(state.get().gatewayId())
                && tenant != null && tenant.id().equals(state.get().tenantId());
        if (!valid) {
            JsonRpcErrorWriter.write(response, 400, -32602, "invalid or unknown session");
            return;
        }

        JsonRpcResponse rpcResponse =
                dispatcher.dispatch(request, new MessageContext(gatewayId, sessionId, tenant.id(), tenant.apiKey()));
        if (rpcResponse != null) {
            registry.send(sessionId, objectMapper.writeValueAsString(rpcResponse.toEventPayload()));
        }
        response.setStatus(HttpServletResponse.SC_ACCEPTED);
    }
}
