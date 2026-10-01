package io.github.renhaowan.platform.gateway.security;

/**
 * 租户级令牌桶限流（Redisson RRateLimiter，RateType.OVERALL：集群维度共享配额）。
 * <p>
 * 故障策略：Redis 不可用时放行（fail-open）并告警——可用性优先，鉴权与业务校验仍在后面兜底。
 */
public interface RateLimitService {

    /**
     * 按接入方 apiKey 获取一次限流许可。
     *
     * @return true=放行；false=超出配额（调用方应返回 429）
     */
    boolean tryAcquire(String apiKey);
}
