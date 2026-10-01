package io.github.renhaowan.platform.gateway.session.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.gateway.message.McpProtocol;
import io.github.renhaowan.platform.gateway.security.GatewayProperties;
import io.github.renhaowan.platform.gateway.session.entity.GatewaySession;
import io.github.renhaowan.platform.gateway.session.mapper.GatewaySessionMapper;
import io.github.renhaowan.platform.gateway.session.GatewaySessionService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * {@link GatewaySessionService} 接口实现：Redis 为会话体主存（30 分钟滑动 TTL），MySQL 留审计轨迹。
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class GatewaySessionServiceImpl implements GatewaySessionService {

    private static final String KEY_PREFIX = McpProtocol.REDIS_KEY_SESSION_PREFIX;
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final GatewaySessionMapper sessionMapper;
    private final RedissonClient redissonClient;
    private final GatewayProperties properties;

    @Override
    public SessionState create(String gatewayId, String transport, Long tenantId) {
        String sessionKey = UUID.randomUUID().toString();
        String instanceId = properties.getInstanceId();
        long now = System.currentTimeMillis();
        SessionState state = new SessionState(sessionKey, tenantId, gatewayId, transport, instanceId, now);

        // 审计行写入失败不阻断会话建立（会话主存在 Redis）
        try {
            GatewaySession audit = new GatewaySession();
            audit.setSessionKey(sessionKey);
            audit.setTenantId(tenantId);
            audit.setGatewayId(gatewayId);
            audit.setTransport(transport);
            audit.setInstanceId(instanceId);
            audit.setCreatedAt(LocalDateTime.now());
            sessionMapper.insert(audit);
        } catch (Exception e) {
            log.warn("session audit insert failed {}: {}", sessionKey, e.getMessage());
        }

        try {
            bucket(sessionKey).set(write(state), TTL);
        } catch (Exception e) {
            log.warn("session persist to redis failed {}: {}", sessionKey, e.getMessage());
        }
        return state;
    }

    /** 校验会话并滑动续期；会话体只在 Redis，Redis 不可用视为无会话。 */
    @Override
    public Optional<SessionState> validate(String sessionKey) {
        try {
            RBucket<String> bucket = bucket(sessionKey);
            String json = bucket.get();
            if (json == null) {
                return Optional.empty();
            }
            bucket.expire(TTL);
            return Optional.of(read(json));
        } catch (Exception e) {
            log.warn("session validate failed {}: {}", sessionKey, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void close(String sessionKey) {
        try {
            bucket(sessionKey).delete();
        } catch (Exception e) {
            log.warn("session close failed {}: {}", sessionKey, e.getMessage());
        }
        GatewaySession update = new GatewaySession();
        update.setExpiredAt(LocalDateTime.now());
        sessionMapper.update(update,
                new UpdateWrapper<GatewaySession>()
                        .eq("session_key", sessionKey).isNull("expired_at"));
    }

    private RBucket<String> bucket(String sessionKey) {
        return redissonClient.getBucket(KEY_PREFIX + sessionKey);
    }

    private String write(SessionState state) {
        try {
            return MAPPER.writeValueAsString(state);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private SessionState read(String json) {
        try {
            return MAPPER.readValue(json, SessionState.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
