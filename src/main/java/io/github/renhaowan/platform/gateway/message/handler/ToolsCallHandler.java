package io.github.renhaowan.platform.gateway.message.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.renhaowan.platform.gateway.forward.GenericHttpForwarder;
import io.github.renhaowan.platform.gateway.message.JsonRpcRequest;
import io.github.renhaowan.platform.gateway.message.JsonRpcResponse;
import io.github.renhaowan.platform.gateway.message.McpProtocol;
import io.github.renhaowan.platform.gateway.message.MessageContext;
import io.github.renhaowan.platform.gateway.message.MessageHandler;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MCP tools/call：定位工具 → 泛化转发 → 按 MCP 规范包装
 * （执行失败不抛协议错误，而是 result.isError=true 让模型自行决策下一步）。
 */
@RequiredArgsConstructor
@Component
public class ToolsCallHandler implements MessageHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistryService registryService;
    private final GenericHttpForwarder forwarder;

    @Override
    public String method() {
        return McpProtocol.METHOD_TOOLS_CALL;
    }

    @Override
    public JsonRpcResponse handle(JsonRpcRequest request, MessageContext context) {
        String name = request.params() == null ? null : request.params().path("name").asText(null);
        if (name == null || name.isBlank()) {
            return JsonRpcResponse.invalidParams(request.id(), McpProtocol.METHOD_TOOLS_CALL + " requires tool name");
        }
        Optional<ToolDefinition> tool = registryService.find(
                context.tenantId(), context.gatewayId(), name);
        if (tool.isEmpty()) {
            return JsonRpcResponse.failure(request.id(), McpProtocol.ERROR_INVALID_PARAMS, "unknown tool: " + name);
        }

        JsonNode arguments = request.params().get("arguments");
        GenericHttpForwarder.ForwardResult result = forwarder.forward(tool.get(), arguments);

        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode content = payload.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", result.success()
                ? result.body()
                : (result.error() == null ? "tool execution failed" : result.error()));
        payload.put("isError", !result.success());
        return JsonRpcResponse.success(request.id(), payload);
    }
}
