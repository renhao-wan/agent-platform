package io.github.renhaowan.platform.agent.tool;

import java.util.List;
import org.springframework.ai.tool.ToolCallback;

/**
 * 动态工具注册表：从工具层拉取工具清单，转换为 Spring AI ToolCallback
 * （仅作 schema 广告位——执行始终走 ToolGateway 手动通道，循环内做确认/事件插桩）。
 * 5 分钟滑动缓存；require_confirm 字段映射敏感标记。
 * <p>
 * 清单与身份无关——无权调用的工具同样在册（角色要求写在描述里），真正的拦截在执行点（闸门 2），
 * 因此一份缓存即可，不必按角色分桶。
 */
public interface DynamicToolRegistry {

    /**
     * 取当前可用工具（整份缓存，过期由调用线程同步刷新）。
     * <p>【白话 Spring AI】返回的 ToolCallback 就是 Spring AI 认识的"工具"对象：
     * getToolDefinition() 提供名称/描述/参数 schema（供下发给模型做选择，即"广告位"），
     * call() 是真正执行入口——这里被手写实现委托给工具层，
     * 从而绕开框架隐式执行、把确认/事件插桩留在 AgentRunner 循环里。
     */
    List<ToolCallback> callbacks();

    /** 敏感工具查询；须在 callbacks() 之后调用，保证查的是同一份清单。 */
    boolean requireConfirm(String toolName);
}
