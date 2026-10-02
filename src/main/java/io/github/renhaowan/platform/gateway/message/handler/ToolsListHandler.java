package io.github.renhaowan.platform.gateway.message.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.renhaowan.platform.gateway.message.JsonRpcRequest;
import io.github.renhaowan.platform.gateway.message.JsonRpcResponse;
import io.github.renhaowan.platform.gateway.message.McpProtocol;
import io.github.renhaowan.platform.gateway.message.MessageContext;
import io.github.renhaowan.platform.gateway.message.MessageHandler;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * MCP tools/list：返回当前租户在指定网关下已启用的工具清单（name/description/inputSchema）。
 * 闸门 1（可见性）：required_role 为 NULL 的工具全员可见；标注了角色的工具仅对该角色可见——
 * 过滤发生在清单下发环节，无权工具对模型完全"不存在"，从源头收窄攻击面。
 */
@RequiredArgsConstructor
@Component
public class ToolsListHandler implements MessageHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ToolRegistryService registryService;

    @Override
    public String method() {
        return McpProtocol.METHOD_TOOLS_LIST;
    }

    @Override
    public JsonRpcResponse handle(JsonRpcRequest request, MessageContext context) {
        List<ToolDefinition> tools = registryService.listEnabled(context.tenantId(), context.gatewayId())
                .stream()
                .filter(tool -> tool.getRequiredRole() == null
                        || tool.getRequiredRole().equals(context.userRole()))
                .toList();
        ObjectNode result = MAPPER.createObjectNode();
        ArrayNode array = result.putArray("tools");
        for (ToolDefinition tool : tools) {
            ObjectNode node = array.addObject();
            node.put("name", tool.getName());
            node.put("description", tool.getDescription() == null ? "" : tool.getDescription());
            node.set("inputSchema", parseSchema(tool.getInputSchema()));
            // 平台扩展字段（非 MCP 规范）：敏感工具标记，Agent 侧据此触发人工确认
            node.put("x-require-confirm", tool.getRequireConfirm() != null && tool.getRequireConfirm() == 1);
        }
        return JsonRpcResponse.success(request.id(), result);
    }

    private JsonNode parseSchema(String inputSchema) {
        try {
            if (inputSchema != null && !inputSchema.isBlank()) {
                return MAPPER.readTree(inputSchema);
            }
        } catch (Exception ignored) {
        }
        return MAPPER.createObjectNode().put("type", "object").putObject("properties");
    }
}
