package io.github.renhaowan.platform.agent.tool;

import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * 动态工具注册表：从 MCP 网关 tools/list 拉取工具，转换为 Spring AI ToolCallback
 * （仅作 schema 广告位——执行始终走 McpGatewayClient 手动通道，循环内做确认/事件插桩）。
 * 按用户角色分桶缓存（5 分钟滑动）；x-require-confirm 扩展字段映射敏感标记。
 * 角色可见性由网关侧过滤（闸门 1），注册表只忠实缓存"该角色看到的清单"。
 */
public interface DynamicToolRegistry {

    /**
     * 取当前用户可用工具（按角色分桶缓存，过期由调用线程同步刷新）。
     * <p>【白话 Spring AI】返回的 ToolCallback 就是 Spring AI 认识的"工具"对象：
     * getToolDefinition() 提供名称/描述/参数 schema（供下发给模型做选择，即"广告位"），
     * call() 是真正执行入口——这里被手写实现委托给网关 tools/call，
     * 从而绕开框架隐式执行、把确认/事件插桩留在 AgentRunner 循环里。
     */
    List<ToolCallback> callbacks(UserPrincipal user);

    /** 敏感工具查询（须与 callbacks 用同一用户，保证查的是该角色看到的清单）。 */
    boolean requireConfirm(String toolName, UserPrincipal user);
}
