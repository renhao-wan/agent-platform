package io.github.renhaowan.platform.agent.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.tool.DynamicToolRegistry;
import io.github.renhaowan.platform.agent.tool.McpGatewayClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Service;

/** DynamicToolRegistry 接口实现：工具清单按用户角色分桶缓存。 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DynamicToolRegistryImpl implements DynamicToolRegistry {

    private static final long REFRESH_INTERVAL_MS = 5 * 60_000L;
    /** 匿名（无身份）缓存桶键 */
    private static final String ANON_KEY = "";

    private final McpGatewayClient gatewayClient;

    /** 角色分桶缓存：STAFF 与 ADMIN 各看到各的清单（网关侧闸门 1 过滤后的结果） */
    private final Map<String, RoleBucket> roleBuckets = new ConcurrentHashMap<>();

    @Override
    public synchronized List<ToolCallback> callbacks(UserPrincipal user) {
        return List.copyOf(bucketOf(user).callbacks);
    }

    @Override
    public boolean requireConfirm(String toolName, UserPrincipal user) {
        // 只读已有缓存不触发刷新：AgentRunner 总是先 callbacks(user) 再进入循环，
        // 敏感标记与可见清单同源（同一次 tools/list），不会出现跨角色串桶
        RoleBucket bucket = roleBuckets.get(bucketKey(user));
        return bucket != null && bucket.confirmFlags.getOrDefault(toolName, false);
    }

    private RoleBucket bucketOf(UserPrincipal user) {
        String key = bucketKey(user);
        RoleBucket bucket = roleBuckets.get(key);
        if (bucket == null || System.currentTimeMillis() - bucket.refreshedAt > REFRESH_INTERVAL_MS) {
            bucket = refresh(user, key);
            roleBuckets.put(key, bucket);
        }
        return bucket;
    }

    private static String bucketKey(UserPrincipal user) {
        return user == null ? ANON_KEY : user.role();
    }

    /**
     * 重拉当前角色的工具清单并整体替换该桶（先构建后切换，失败保留旧数据——注册失败不影响在跑对话）。
     */
    private RoleBucket refresh(UserPrincipal user, String key) {
        RoleBucket previous = roleBuckets.get(key);
        try {
            List<ToolCallback> rebuilt = new ArrayList<>();
            Map<String, Boolean> flags = new ConcurrentHashMap<>();
            for (JsonNode tool : gatewayClient.listTools(user)) {
                String name = tool.path("name").asText();
                String description = tool.path("description").asText("");
                String schema = tool.path("inputSchema").toString();
                boolean requireConfirm = tool.path("x-require-confirm").asBoolean(false);
                rebuilt.add(new GatewayToolCallback(name, description, schema, gatewayClient));
                flags.put(name, requireConfirm);
            }
            log.info("tool registry refreshed for role [{}]: {} tools", key.isEmpty() ? "-" : key, rebuilt.size());
            return new RoleBucket(rebuilt, flags, System.currentTimeMillis());
        } catch (Exception e) {
            log.warn("tool registry refresh failed for role [{}], keep previous: {}", key, e.getMessage());
            return previous != null ? previous : new RoleBucket(List.of(), new ConcurrentHashMap<>(), 0L);
        }
    }

    /** 单个角色的缓存桶：可见回调 + 敏感标记 + 刷新时间。 */
    private record RoleBucket(List<ToolCallback> callbacks, Map<String, Boolean> confirmFlags, long refreshedAt) {
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
            // ToolCallback 执行点不在 ReAct 循环内（Spring AI 隐式执行），本项目不走该路径；
            // 真正执行在 AgentRunner 手动通道，角色随调用显式传入
            return client.callTool(name, toolInput, null);
        }
    }
}
