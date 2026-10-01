package io.github.renhaowan.platform.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.renhaowan.platform.agent.tool.DynamicToolRegistry;
import io.github.renhaowan.platform.agent.tool.McpGatewayClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

/** DynamicToolRegistry 接口实现。 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DynamicToolRegistryImpl implements DynamicToolRegistry {

    private static final long REFRESH_INTERVAL_MS = 5 * 60_000L;

    private final McpGatewayClient gatewayClient;
    private final List<ToolCallback> callbacks = new CopyOnWriteArrayList<>();
    private final Map<String, Boolean> requireConfirmByName = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long refreshedAt = 0;

    @Override
    public synchronized List<ToolCallback> callbacks() {
        if (System.currentTimeMillis() - refreshedAt > REFRESH_INTERVAL_MS) {
            refresh();
        }
        return List.copyOf(callbacks);
    }

    @Override
    public boolean requireConfirm(String toolName) {
        return requireConfirmByName.getOrDefault(toolName, false);
    }

    /**
     * 全量重拉工具清单并原子替换缓存（先构建后切换，失败保留旧数据——注册失败不影响在跑对话）。
     */
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
