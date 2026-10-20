package io.github.renhaowan.platform.agent.tool.impl;

import io.github.renhaowan.platform.agent.tool.DynamicToolRegistry;
import io.github.renhaowan.platform.agent.tool.ToolGateway;
import io.github.renhaowan.platform.gateway.registry.ToolCatalogChangedEvent;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

/** DynamicToolRegistry 接口实现：工具清单整份缓存。 */
@Slf4j
@RequiredArgsConstructor
@Service
public class DynamicToolRegistryImpl implements DynamicToolRegistry {

    private static final long REFRESH_INTERVAL_MS = 5 * 60_000L;

    private final ToolGateway toolGateway;

    /** 清单与身份无关（角色差异在执行点判定），一份缓存即可。 */
    private volatile Bucket bucket = new Bucket(List.of(), Map.of(), 0L);

    @Override
    public List<ToolCallback> callbacks() {
        Bucket current = bucket;
        if (System.currentTimeMillis() - current.refreshedAt() > REFRESH_INTERVAL_MS) {
            current = refresh();
        }
        return List.copyOf(current.callbacks());
    }

    @Override
    public boolean requireConfirm(String toolName) {
        // 只读已有缓存不触发刷新：AgentRunner 总是先 callbacks() 再进入循环，敏感标记与清单同源
        return bucket.confirmFlags().getOrDefault(toolName, false);
    }

    /** 契约对账完成后立即换清单，不必等 5 分钟缓存过期。 */
    @org.springframework.context.event.EventListener
    public void onCatalogChanged(ToolCatalogChangedEvent event) {
        log.info("工具目录已由来源 {} 更新，立即刷新清单", event.sourceKey());
        refresh();
    }

    /**
     * 重拉清单并整体替换该桶（先构建后切换，失败保留旧数据——查库失败不影响在跑对话）。
     * <p>故障形态提示：工具清单来自数据库，MySQL 不可用时首次刷新会拿到空桶，
     * 表现为"模型看不到任何工具"，而非报错。
     */
    private synchronized Bucket refresh() {
        Bucket previous = bucket;
        try {
            List<ToolCallback> rebuilt = new ArrayList<>();
            Map<String, Boolean> flags = new ConcurrentHashMap<>();
            for (ToolDefinition tool : toolGateway.listTools()) {
                String name = tool.getName();
                boolean requireConfirm = tool.getRequireConfirm() != null && tool.getRequireConfirm() == 1;
                rebuilt.add(new GatewayToolCallback(tool));
                flags.put(name, requireConfirm);
            }
            log.info("tool registry refreshed: {} tools", rebuilt.size());
            Bucket refreshed = new Bucket(rebuilt, flags, System.currentTimeMillis());
            bucket = refreshed;
            return refreshed;
        } catch (Exception e) {
            log.warn("tool registry refresh failed, keep previous: {}", e.getMessage());
            return previous;
        }
    }

    /** 缓存桶：回调清单 + 敏感标记 + 刷新时间。 */
    private record Bucket(List<ToolCallback> callbacks, Map<String, Boolean> confirmFlags, long refreshedAt) {
    }

    /** schema 广告位；执行走 AgentRunner 手动通道，本类的 call() 永不执行工具。 */
    static class GatewayToolCallback implements ToolCallback {

        private final ToolDefinition tool;

        GatewayToolCallback(ToolDefinition tool) {
            this.tool = tool;
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return org.springframework.ai.tool.definition.ToolDefinition.builder()
                    .name(tool.getName())
                    .description(describe(tool))
                    .inputSchema(tool.getInputSchema() == null ? "{}" : tool.getInputSchema())
                    .build();
        }

        /**
         * 权限要求写进描述：模型据此知道"这个能力存在、但当前身份不够"，可先告知用户而非盲目尝试。
         */
        private static String describe(ToolDefinition tool) {
            String description = tool.getDescription() == null ? "" : tool.getDescription();
            String requiredPermission = tool.getRequiredPermission();
            if (requiredPermission == null || requiredPermission.isBlank()) {
                return description;
            }
            return description + "（此操作需要 " + requiredPermission + " 权限）";
        }

        @Override
        public String call(String toolInput) {
            // 隐式执行在本项目被显式关闭（internalToolExecutionEnabled(false)），本方法不应被调用。
            // 这里不能委托网关：网关契约要求带用户身份，而 ToolCallback 没有身份参数；
            // 静默以 null 身份执行会绕过闸门 2。抛错比越权安全。
            throw new UnsupportedOperationException(
                    "tool execution is handled by AgentRunner; ToolCallback.call() must not be invoked");
        }
    }
}
