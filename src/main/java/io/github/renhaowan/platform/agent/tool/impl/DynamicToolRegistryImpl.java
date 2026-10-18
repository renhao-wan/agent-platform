package io.github.renhaowan.platform.agent.tool.impl;

import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.tool.DynamicToolRegistry;
import io.github.renhaowan.platform.agent.tool.ToolGateway;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/** DynamicToolRegistry 接口实现：工具清单按用户角色分桶缓存。 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DynamicToolRegistryImpl implements DynamicToolRegistry {

    private static final long REFRESH_INTERVAL_MS = 5 * 60_000L;
    /** 匿名（无身份）缓存桶键 */
    private static final String ANON_KEY = "";

    private final ToolGateway toolGateway;

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
     * <p>故障形态提示：工具清单来自数据库，MySQL 不可用时首次刷新会拿到空桶，
     * 表现为"模型看不到任何工具"，而非报错。
     */
    private RoleBucket refresh(UserPrincipal user, String key) {
        RoleBucket previous = roleBuckets.get(key);
        try {
            List<ToolCallback> rebuilt = new ArrayList<>();
            Map<String, Boolean> flags = new ConcurrentHashMap<>();
            for (ToolDefinition tool : toolGateway.listTools(user)) {
                String name = tool.getName();
                boolean requireConfirm = tool.getRequireConfirm() != null && tool.getRequireConfirm() == 1;
                rebuilt.add(new GatewayToolCallback(tool, toolGateway));
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

    /** schema 广告位 + 执行委托 ToolGateway 的手写 ToolCallback。 */
    static class GatewayToolCallback implements ToolCallback {

        private final ToolDefinition tool;
        private final ToolGateway gateway;

        GatewayToolCallback(ToolDefinition tool, ToolGateway gateway) {
            this.tool = tool;
            this.gateway = gateway;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name(tool.getName())
                    .description(tool.getDescription() == null ? "" : tool.getDescription())
                    .inputSchema(tool.getInputSchema() == null ? "{}" : tool.getInputSchema())
                    .build();
        }

        @Override
        public String call(String toolInput) {
            // ToolCallback 执行点不在 ReAct 循环内（Spring AI 隐式执行），本项目不走该路径；
            // 真正执行在 AgentRunner 手动通道，角色随调用显式传入
            return gateway.callTool(tool.getName(), toolInput, null);
        }
    }
}
