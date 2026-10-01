package io.github.renhaowan.platform.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import io.github.renhaowan.platform.gateway.security.impl.RateLimitServiceImpl;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RedissonClient;

@ExtendWith(MockitoExtension.class)
class RateLimitServiceTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RRateLimiter rateLimiter;

    private final GatewayProperties properties = new GatewayProperties();

    @Test
    void acquiredWhenLimiterPermits() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire()).thenReturn(true);

        assertThat(new RateLimitServiceImpl(redissonClient, properties).tryAcquire("key-1")).isTrue();
    }

    @Test
    void rejectedWhenLimiterExhausted() {
        when(redissonClient.getRateLimiter(anyString())).thenReturn(rateLimiter);
        when(rateLimiter.tryAcquire()).thenReturn(false);

        assertThat(new RateLimitServiceImpl(redissonClient, properties).tryAcquire("key-2")).isFalse();
    }

    @Test
    void failOpenWhenRedisUnavailable() {
        when(redissonClient.getRateLimiter(anyString())).thenThrow(new IllegalStateException("redis down"));

        assertThat(new RateLimitServiceImpl(redissonClient, properties).tryAcquire("key-3")).isTrue();
    }
}
