package io.github.renhaowan.platform.gateway.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import io.github.renhaowan.platform.gateway.security.GatewayProperties;

/**
 * MCP 网关会话生命周期：Redis 为会话体主存（30 分钟滑动 TTL），MySQL 留审计轨迹。
 */
@Service
public class GatewaySessionService {

    private static final Logger log = LoggerFactory.getLogger(GatewaySessionService.class);

    private static final String KEY_PREFIX = "gw:session:";
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record SessionState(String sessionKey, Long tenantId, String gatewayId,
                               String transport, String instanceId, long createdAtEpochMs) {
    }

    private final GatewaySessionMapper sessionMapper;
    private final RedissonClient redissonClient;
    private final String instanceId;

    public GatewaySessionService(GatewaySessionMapper sessionMapper,
                                 RedissonClient redissonClient,
                                 GatewayProperties properties) {
        this.sessionMapper = sessionMapper;
        this.redissonClient = redissonClient;
        this.instanceId = properties.getInstanceId();
    }

    public SessionState create(String gatewayId, String transport, Long tenantId) {
        String sessionKey = UUID.randomUUID().toString();
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
            audit.setCreatedAt(java.time.LocalDateTime.now());
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

    public void close(String sessionKey) {
        try {
            bucket(sessionKey).delete();
        } catch (Exception e) {
            log.warn("session close failed {}: {}", sessionKey, e.getMessage());
        }
        GatewaySession update = new GatewaySession();
        update.setExpiredAt(java.time.LocalDateTime.now());
        sessionMapper.update(update,
                new com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper<GatewaySession>()
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
