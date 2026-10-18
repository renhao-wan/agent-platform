-- agent_platform schema（spring.sql.init 幂等执行，随里程碑追加）
--
-- 重构清理（幂等，不写裸 DROP）：平台已无租户与协议会话概念，
-- tenant / gateway_session 两张表与 tool_definition.tenant_id 列在此下线。
SET @t = (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tenant');
SET @ddl = IF(@t > 0, 'DROP TABLE tenant', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @t = (SELECT COUNT(*) FROM information_schema.TABLES
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='gateway_session');
SET @ddl = IF(@t > 0, 'DROP TABLE gateway_session', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- M1.6：工具注册表（OpenAPI 导入产物，Agent 据此发现可用工具）
CREATE TABLE IF NOT EXISTS tool_definition (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
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
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '工具定义';

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_definition' AND COLUMN_NAME='tenant_id');
SET @ddl = IF(@col_exists > 0, 'ALTER TABLE tool_definition DROP COLUMN tenant_id', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

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

-- M4.1：平台用户（登录与三级权限闸门的身份源；密码存 SHA-256 十六进制小写）
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'SHA-256 十六进制小写',
    role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN | STAFF',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '平台用户';

-- M4.1：会话归属用户（幂等：information_schema 守卫，已存在则跳过；
-- NULL 表示历史遗留的无主会话，续聊放行但新会话一律绑定）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS 
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='chat_session' AND COLUMN_NAME='user_id');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE chat_session ADD COLUMN user_id BIGINT NULL AFTER title', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- M4.2：工具级角色闸门（幂等：information_schema 守卫，已存在则跳过）。
-- required_role NULL=全员可用；ADMIN|STAFF=仅该角色可见可调
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS 
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_definition' AND COLUMN_NAME='required_role');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE tool_definition ADD COLUMN required_role VARCHAR(16) NULL AFTER require_confirm', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
