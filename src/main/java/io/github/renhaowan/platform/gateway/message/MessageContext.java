package io.github.renhaowan.platform.gateway.message;

/**
 * 一次 MCP 消息处理的上下文（鉴权过滤器/传输控制器填充）。
 * userRole 来自 X-User-Role 请求头（Agent 自环时由当前会话用户填入），
 * 是工具可见性（tools/list）与执行点鉴权（tools/call）的数据源；null 表示匿名调用方。
 */
public record MessageContext(String gatewayId, String sessionKey, Long tenantId, String apiKey, String userRole) {

    /** 兼容构造：匿名调用方（无用户角色语义的场景）。 */
    public MessageContext(String gatewayId, String sessionKey, Long tenantId, String apiKey) {
        this(gatewayId, sessionKey, tenantId, apiKey, null);
    }
}
