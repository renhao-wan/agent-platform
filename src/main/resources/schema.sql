-- agent_platform schema（spring.sql.init 幂等执行，随里程碑追加）
-- M1.2：MCP 网关租户
CREATE TABLE IF NOT EXISTS tenant (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    api_key    CHAR(36)    NOT NULL,
    status     TINYINT     NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_api_key (api_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'MCP 网关租户';

-- M1.3：MCP 网关会话（审计轨迹；会话体在 Redis，TTL 30 分钟滑动）
CREATE TABLE IF NOT EXISTS gateway_session (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key CHAR(36)   NOT NULL,
    tenant_id  BIGINT      NOT NULL,
    gateway_id VARCHAR(64) NOT NULL,
    transport  VARCHAR(16) NOT NULL COMMENT 'SSE / STREAMABLE',
    instance_id VARCHAR(64) NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expired_at DATETIME    NULL,
    UNIQUE KEY uk_session_key (session_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'MCP 网关会话审计';
