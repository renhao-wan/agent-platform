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
    public String request(String sessionKey, long userId, String toolName, String argumentsJson) {
        String token = UUID.randomUUID().toString();
        futures.put(token, new CompletableFuture<>());
        pendings.put(token, new PendingCall(userId, sessionKey, toolName, argumentsJson));
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
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            // 超时/中断/异常时清理；正常完成由 complete 负责，重复 remove 无副作用
            if (!future.isDone()) {
                pendings.remove(token);
                futures.remove(token);
            }
        }
    }

    @Override
    public boolean complete(String token, boolean approved) {
        CompletableFuture<Boolean> future = futures.get(token);
        if (future == null) {
            return false;
        }
        // 先唤醒等待者再移除：反过来会在"确认已生效"与"await 读 map"之间留一个窗口，
        // 极快点击可能被当成拒绝
        future.complete(approved);
        pendings.remove(token);
        futures.remove(token);
        return true;
    }

    @Override
    public PendingCall pendingOf(String token) {
        return pendings.get(token);
    }
}
