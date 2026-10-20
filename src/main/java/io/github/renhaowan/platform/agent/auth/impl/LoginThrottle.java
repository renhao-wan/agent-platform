package io.github.renhaowan.platform.agent.auth.impl;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 登录失败限流（单实例内存实现）：同一用户名 5 分钟内连续失败 5 次即暂时拒绝。
 * <p>
 * 计的是"失败次数"而非"请求次数"，正常用户输错一次不会被误伤；成功登录立即清零。
 * 用户名不存在同样计数，避免通过限流行为反推账号是否存在。
 */
@Component
public class LoginThrottle {

    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(5);
    private static final int MAX_TRACKED_USERS = 10_000;

    private record Attempts(long windowStartMs, int count) {
    }

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    public boolean isBlocked(String username) {
        Attempts current = attempts.get(key(username));
        if (current == null) {
            return false;
        }
        if (expired(current, System.currentTimeMillis())) {
            attempts.remove(key(username), current);
            return false;
        }
        return current.count() >= MAX_FAILURES;
    }

    public void onFailure(String username) {
        long now = System.currentTimeMillis();
        attempts.compute(key(username), (ignored, old) -> old == null || expired(old, now)
                ? new Attempts(now, 1)
                : new Attempts(old.windowStartMs(), old.count() + 1));
        if (attempts.size() > MAX_TRACKED_USERS) {
            attempts.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        }
    }

    public void onSuccess(String username) {
        attempts.remove(key(username));
    }

    private static boolean expired(Attempts attempts, long now) {
        return now - attempts.windowStartMs() >= WINDOW.toMillis();
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(java.util.Locale.ROOT);
    }
}
