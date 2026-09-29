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
