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

    /**
     * 取当前可用工具（带 5 分钟滑动缓存，过期由调用线程同步刷新）。
     * <p>【白话 Spring AI】返回的 ToolCallback 就是 Spring AI 认识的"工具"对象：
     * getToolDefinition() 提供名称/描述/参数 schema（供下发给模型做选择，即"广告位"），
     * call() 是真正执行入口——这里被手写实现委托给网关 tools/call，
     * 从而绕开框架隐式执行、把确认/事件插桩留在 AgentRunner 循环里。
     */
    public synchronized List<ToolCallback> callbacks() {
        if (System.currentTimeMillis() - refreshedAt > REFRESH_INTERVAL_MS) {
            refresh();
        }
        return List.copyOf(callbacks);
    }

    /** 敏感工具查询：true 表示调用前需用户人工确认（来源：工具注册时的 x-require-confirm 扩展字段）。 */
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
