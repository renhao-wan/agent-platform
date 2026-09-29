package io.github.renhaowan.platform.gateway.message;

/**
 * 一次 MCP 消息处理的上下文（鉴权过滤器填充）。
 */
public record MessageContext(String gatewayId, String sessionKey, Long tenantId, String apiKey) {
}
