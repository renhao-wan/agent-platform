package io.github.renhaowan.platform.gateway.security;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * 租户级令牌桶限流（Redisson RRateLimiter，RateType.OVERALL：集群维度共享配额）。
 * <p>
 * 故障策略：Redis 不可用时放行（fail-open）并告警——可用性优先，鉴权与业务校验仍在后面兜底。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class RateLimitService {

    private static final String KEY_PREFIX = "gw:ratelimit:";

    private final RedissonClient redissonClient;
    private final GatewayProperties properties;

    public boolean tryAcquire(String apiKey) {
        try {
            RRateLimiter limiter = redissonClient.getRateLimiter(KEY_PREFIX + apiKey);
            limiter.trySetRate(RateType.OVERALL,
                    properties.getRateLimit().getPermitsPerMinute(),
                    1, RateIntervalUnit.MINUTES);
            return limiter.tryAcquire();
        } catch (Exception e) {
            log.warn("rate limiter unavailable, fail-open for {}: {}", mask(apiKey), e.getMessage());
            return true;
        }
    }

    private String mask(String apiKey) {
        return apiKey.length() <= 8 ? "***" : apiKey.substring(0, 8) + "***";
    }
}
