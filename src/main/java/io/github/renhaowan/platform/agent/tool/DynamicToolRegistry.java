package io.github.renhaowan.platform.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.renhaowan.platform.agent.AgentProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

/**
 * 动态工具注册表：从 MCP 网关 tools/list 拉取工具，转换为 Spring AI ToolCallback
 * （仅作 schema 广告位——执行始终走 McpGatewayClient 手动通道，循环内做确认/事件插桩）。
 * 缓存 5 分钟；x-require-confirm 扩展字段映射敏感标记。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DynamicToolRegistry {

    private static final long REFRESH_INTERVAL_MS = 5 * 60_000L;

    private final McpGatewayClient gatewayClient;
    private final List<ToolCallback> callbacks = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> requireConfirmByName = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long refreshedAt = 0;

    public synchronized List<ToolCallback> callbacks() {
        if (System.currentTimeMillis() - refreshedAt > REFRESH_INTERVAL_MS) {
            refresh();
        }
        return List.copyOf(callbacks);
    }

    public boolean requireConfirm(String toolName) {
        return requireConfirmByName.getOrDefault(toolName, false);
    }

    public synchronized void refresh() {
        try {
            List<ToolCallback> rebuilt = new java.util.ArrayList<>();
            Map<String, Boolean> flags = new java.util.concurrent.ConcurrentHashMap<>();
            for (JsonNode tool : gatewayClient.listTools()) {
                String name = tool.path("name").asText();
                String description = tool.path("description").asText("");
                String schema = tool.path("inputSchema").toString();
                boolean requireConfirm = tool.path("x-require-confirm").asBoolean(false);
                rebuilt.add(new GatewayToolCallback(name, description, schema, gatewayClient));
                flags.put(name, requireConfirm);
            }
            callbacks.clear();
            callbacks.addAll(rebuilt);
            requireConfirmByName.clear();
            requireConfirmByName.putAll(flags);
            refreshedAt = System.currentTimeMillis();
            log.info("tool registry refreshed: {} tools", rebuilt.size());
        } catch (Exception e) {
            log.warn("tool registry refresh failed, keep previous: {}", e.getMessage());
        }
    }

    /** schema 广告位 + 执行委托网关的手写 ToolCallback。 */
    static class GatewayToolCallback implements ToolCallback {

        private final String name;
        private final String description;
        private final String inputSchema;
        private final McpGatewayClient client;

        GatewayToolCallback(String name, String description, String inputSchema, McpGatewayClient client) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
            this.client = client;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return ToolDefinition.builder()
                    .name(name)
                    .description(description)
                    .inputSchema(inputSchema)
                    .build();
        }

        @Override
        public String call(String toolInput) {
            return client.callTool(name, toolInput);
        }
    }
}
