package io.github.renhaowan.platform.agent.confirm;

/**
 * 敏感操作人工确认：AgentRunner 在工具执行前挂起（CompletableFuture），
 * 前端携带 confirmToken 调 POST /api/v1/confirm 恢复；超时视为拒绝。
 * M2 为单实例内存实现（重启丢失待确认项，视为拒绝，语义安全）；多实例化时迁移 Redis。
 */
public interface ConfirmManager {

    record PendingCall(String sessionKey, String toolName, String argumentsJson) {
    }

    String request(String sessionKey, String toolName, String argumentsJson);

    Boolean await(String token, int timeoutSeconds);

    boolean complete(String token, boolean approved);

    PendingCall pendingOf(String token);
}
