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
 * 闸门 2（执行点）：tool.required_role 非空且 ≠ 用户角色时拒绝执行——同样以 isError=true
 * 包装"权限不足"文本返回，模型可读、ReAct 循环不断，由模型向用户转述原因。
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

        // 闸门 2：角色不符 → 拒绝执行，但不走协议错误（保持 Agent 循环完整可继续）
        String requiredRole = tool.get().getRequiredRole();
        if (requiredRole != null && !requiredRole.equals(context.userRole())) {
            return JsonRpcResponse.success(request.id(), deniedResult(requiredRole));
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

    /** 权限拒绝的 MCP 结果包装：isError=true + 模型可读文本。 */
    private ObjectNode deniedResult(String requiredRole) {
        ObjectNode payload = MAPPER.createObjectNode();
        ArrayNode content = payload.putArray("content");
        ObjectNode text = content.addObject();
        text.put("type", "text");
        text.put("text", "权限不足：需要 " + requiredRole + " 角色");
        payload.put("isError", true);
        return payload;
    }
}
