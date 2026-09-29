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

-- M1.6：工具注册表（OpenAPI 导入产物，Agent 经 tools.list 发现）
CREATE TABLE IF NOT EXISTS tool_definition (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    tenant_id    BIGINT       NOT NULL,
    gateway_id   VARCHAR(64)  NOT NULL,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(512) NULL,
    http_method  VARCHAR(8)   NOT NULL,
    url_template VARCHAR(512) NOT NULL,
    input_schema JSON         NULL,
    require_confirm TINYINT   NOT NULL DEFAULT 0,
    enabled      TINYINT      NOT NULL DEFAULT 1,
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME     NULL,
    UNIQUE KEY uk_gw_name (gateway_id, name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'MCP 网关工具定义';

-- M2.1：Agent 会话与消息（sessionKey 为聚合根键）
CREATE TABLE IF NOT EXISTS chat_session (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key CHAR(36)   NOT NULL,
    title      VARCHAR(128) NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chat_session_key (session_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 会话';

CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key CHAR(36)   NOT NULL,
    role       VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT/TOOL/SYSTEM',
    content    TEXT        NOT NULL,
    tool_name  VARCHAR(128) NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_msg_session (session_key, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 对话消息';

-- M2.5：上下文裁剪保留的关键里程碑
CREATE TABLE IF NOT EXISTS checkpoint (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key CHAR(36)   NOT NULL,
    summary    VARCHAR(512) NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '会话里程碑';

-- M2.5：LLM Token 用量打点（成本实验数据源）
CREATE TABLE IF NOT EXISTS llm_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key CHAR(36)  NOT NULL,
    phase      VARCHAR(32) NOT NULL COMMENT 'decide/summarize 等',
    model      VARCHAR(64) NOT NULL,
    prompt_tokens     INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_usage_session (session_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'LLM 用量打点';

-- M3.6：工具级服务凭证（幂等：information_schema 守卫，已存在则跳过）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS 
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_definition' AND COLUMN_NAME='auth_header_name');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE tool_definition ADD COLUMN auth_header_name VARCHAR(64) NULL AFTER input_schema, ADD COLUMN auth_header_value VARCHAR(256) NULL AFTER auth_header_name', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
