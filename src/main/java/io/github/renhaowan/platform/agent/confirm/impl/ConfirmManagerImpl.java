package io.github.renhaowan.platform.agent.confirm.impl;

import io.github.renhaowan.platform.agent.confirm.ConfirmManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** ConfirmManager 接口实现。 */
@Slf4j
@Service
public class ConfirmManagerImpl implements ConfirmManager {

    private final Map<String, PendingCall> pendings = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<Boolean>> futures = new ConcurrentHashMap<>();

    @Override
    public String request(String sessionKey, String toolName, String argumentsJson) {
        String token = UUID.randomUUID().toString();
        futures.put(token, new CompletableFuture<>());
        pendings.put(token, new PendingCall(sessionKey, toolName, argumentsJson));
        return token;
    }

    @Override
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

    @Override
    public boolean complete(String token, boolean approved) {
        CompletableFuture<Boolean> future = futures.remove(token);
        if (future == null) {
            return false;
        }
        pendings.remove(token);
        future.complete(approved);
        return true;
    }

    @Override
    public PendingCall pendingOf(String token) {
        return pendings.get(token);
    }
}
