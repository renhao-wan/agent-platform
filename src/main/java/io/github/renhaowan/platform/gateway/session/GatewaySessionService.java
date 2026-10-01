package io.github.renhaowan.platform.gateway.session;

import java.util.Optional;

/**
 * MCP 网关会话生命周期：Redis 为会话体主存（30 分钟滑动 TTL），MySQL 留审计轨迹。
 */
public interface GatewaySessionService {

    /** 一次 MCP 会话的状态快照。 */
    record SessionState(String sessionKey, Long tenantId, String gatewayId,
                        String transport, String instanceId, long createdAtEpochMs) {
    }

    /** 创建会话：Redis 写入 + MySQL 审计（best-effort）。 */
    SessionState create(String gatewayId, String transport, Long tenantId);

    /** 校验会话并滑动续期；会话体只在 Redis，Redis 不可用视为无会话。 */
    Optional<SessionState> validate(String sessionKey);

    /** 关闭会话：Redis 删除 + 审计盖章。 */
    void close(String sessionKey);
}
