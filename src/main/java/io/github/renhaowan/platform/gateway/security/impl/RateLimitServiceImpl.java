package io.github.renhaowan.platform.gateway.security.impl;

import io.github.renhaowan.platform.gateway.security.GatewayProperties;
import io.github.renhaowan.platform.gateway.security.RateLimitService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * {@link RateLimitService} 接口实现：Redisson 令牌桶。
 * <p>
 * 故障策略：Redis 不可用时放行（fail-open）并告警——可用性优先，鉴权与业务校验仍在后面兜底。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class RateLimitServiceImpl implements RateLimitService {

    private static final String KEY_PREFIX = "gw:ratelimit:";

    private final RedissonClient redissonClient;
    private final GatewayProperties properties;

    @Override
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
