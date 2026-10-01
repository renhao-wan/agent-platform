package io.github.renhaowan.platform.agent.confirm;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 敏感操作人工确认：AgentRunner 在工具执行前挂起（CompletableFuture），
 * 前端携带 confirmToken 调 POST /api/v1/confirm 恢复；超时视为拒绝。
 * M2 为单实例内存实现（重启丢失待确认项，视为拒绝，语义安全）；多实例化时迁移 Redis。
 */
@Slf4j
@Service
public class ConfirmManager {

    public record PendingCall(String sessionKey, String toolName, String argumentsJson) {
    }

    private final Map<String, PendingCall> pendings = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> futures = new ConcurrentHashMap<>();

    public String request(String sessionKey, String toolName, String argumentsJson) {
        String token = UUID.randomUUID().toString();
        futures.put(token, new CompletableFuture<>());
        pendings.put(token, new PendingCall(sessionKey, toolName, argumentsJson));
        return token;
    }

    public Boolean await(String token, int timeoutSeconds) {
        CompletableFuture<Boolean> future = futures.get(token);
        if (future == null) {
            return false;
        }
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("confirm timeout, treat as rejected: {}", token);
            pendings.remove(token);
            futures.remove(token);
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public boolean complete(String token, boolean approved) {
        CompletableFuture<Boolean> future = futures.remove(token);
        if (future == null) {
            return false;
        }
        pendings.remove(token);
        future.complete(approved);
        return true;
    }

    public PendingCall pendingOf(String token) {
        return pendings.get(token);
    }
}
