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

-- 契约对账：工具归属的来源、契约指纹，以及"契约是否仍声明"。
-- present 由同步维护；enabled 是人工开关，同步永不覆盖——两者分开才能既收敛契约又保留人工禁用。
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_definition' AND COLUMN_NAME='source_key');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_definition ADD COLUMN source_key VARCHAR(128) NULL AFTER name', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_definition' AND COLUMN_NAME='contract_hash');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_definition ADD COLUMN contract_hash CHAR(64) NULL AFTER source_key', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_definition' AND COLUMN_NAME='present');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_definition ADD COLUMN present TINYINT NOT NULL DEFAULT 1 AFTER enabled', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @idx_exists = (SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_definition' AND INDEX_NAME='idx_tool_source');
SET @ddl = IF(@idx_exists = 0,
  'ALTER TABLE tool_definition ADD INDEX idx_tool_source (gateway_id, source_key)', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_source' AND COLUMN_NAME='last_stale_tools');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_source ADD COLUMN last_stale_tools TEXT NULL AFTER last_stale', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_source' AND COLUMN_NAME='last_breaking_changes');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_source ADD COLUMN last_breaking_changes TEXT NULL AFTER last_breaking', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 每个契约来源的最近一次对账结果（last-known-good 与失败原因都落库，重启不丢）
CREATE TABLE IF NOT EXISTS tool_source (
    source_key      VARCHAR(128) NOT NULL PRIMARY KEY,
    gateway_id      VARCHAR(64)  NOT NULL,
    contract_url    VARCHAR(512) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    contract_hash   CHAR(64)     NULL,
    tool_count      INT          NOT NULL DEFAULT 0,
    last_added      INT          NOT NULL DEFAULT 0,
    last_updated    INT          NOT NULL DEFAULT 0,
    last_stale      INT          NOT NULL DEFAULT 0,
    last_stale_tools TEXT        NULL,
    last_breaking   INT          NOT NULL DEFAULT 0,
    last_breaking_changes TEXT   NULL,
    last_attempt_at DATETIME     NULL,
    last_success_at DATETIME     NULL,
    last_error      VARCHAR(512) NULL
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '契约来源与最近一次对账状态';

-- 来源注册表（ADR-009 演进第一步）：配置只做首次种子，管理端可在运行时增删改。
-- origin 决定所有权：CONFIG 由启动种子刷新，管理员编辑后提升为 ADMIN，配置不再覆盖。
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_source' AND COLUMN_NAME='origin');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_source ADD COLUMN origin VARCHAR(16) NOT NULL DEFAULT ''CONFIG'' AFTER contract_url', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_source' AND COLUMN_NAME='enabled');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_source ADD COLUMN enabled TINYINT NOT NULL DEFAULT 1 AFTER origin', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_source' AND COLUMN_NAME='base_url');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_source ADD COLUMN base_url VARCHAR(512) NULL AFTER contract_url', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_source' AND COLUMN_NAME='service_key_name');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_source ADD COLUMN service_key_name VARCHAR(64) NULL AFTER base_url', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_source' AND COLUMN_NAME='service_key_value');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_source ADD COLUMN service_key_value VARCHAR(256) NULL AFTER service_key_name', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_definition' AND COLUMN_NAME='tenant_id');
SET @ddl = IF(@col_exists > 0, 'ALTER TABLE tool_definition DROP COLUMN tenant_id', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- M2.1：Agent 会话与消息（sessionKey 为聚合根键）
CREATE TABLE IF NOT EXISTS chat_session (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key CHAR(36)   NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chat_session_key (session_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 会话';

CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_key CHAR(36)   NOT NULL,
    role       VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT/TOOL/SYSTEM',
    content    TEXT        NOT NULL,
    tool_name  VARCHAR(128) NULL,
    tool_arguments TEXT     NULL COMMENT 'TOOL 消息的调用参数（审计用，超长截断）',
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_msg_session (session_key, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'Agent 对话消息';

-- 存量库补列：TOOL 消息的调用参数（审计用）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='chat_message' AND COLUMN_NAME='tool_arguments');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE chat_message ADD COLUMN tool_arguments TEXT NULL AFTER tool_name', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

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

-- M4.1：平台用户（登录与三级权限闸门的身份源；密码存 PBKDF2，兼容历史 SHA-256 摘要）
CREATE TABLE IF NOT EXISTS app_user (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(32)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL COMMENT 'PBKDF2 摘要（pbkdf2$iter$salt$hash），兼容历史 SHA-256',
    role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN | STAFF',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_app_user_username (username)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '平台用户';

-- M4.1：会话归属用户（幂等：information_schema 守卫，已存在则跳过；
-- NULL 表示历史遗留的无主会话，续聊放行但新会话一律绑定）
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='chat_session' AND COLUMN_NAME='user_id');
SET @ddl = IF(@col_exists = 0, 'ALTER TABLE chat_session ADD COLUMN user_id BIGINT NULL AFTER session_key', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 删 title：它唯一的下游是「会话列表」的展示名，而会话列表不做——
-- createSession 辛苦截出来的前 20 字，全项目没有一处读它。
-- 必须排在 user_id 那条之后：那条的定位锚点原本是 title。
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='chat_session' AND COLUMN_NAME='title');
SET @ddl = IF(@col_exists > 0, 'ALTER TABLE chat_session DROP COLUMN title', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- RBAC：工具执行点按权限码鉴权（如 booking:cancel / admin:tool），由业务契约
-- x-required-permission 声明，平台只读不解释。
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_definition' AND COLUMN_NAME='required_permission');
SET @ddl = IF(@col_exists = 0,
  'ALTER TABLE tool_definition ADD COLUMN required_permission VARCHAR(128) NULL AFTER require_confirm', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 删除历史列 required_role：权限判定已统一到 required_permission。
SET @col_exists = (SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA='agent_platform' AND TABLE_NAME='tool_definition' AND COLUMN_NAME='required_role');
SET @ddl = IF(@col_exists > 0, 'ALTER TABLE tool_definition DROP COLUMN required_role', 'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- RBAC：角色与角色-权限映射。用户保持单角色（app_user.role -> app_role.code），
-- 不引入用户-角色多对多；权限码由平台内置（admin:*）与业务契约（x-required-permission）共同构成。
CREATE TABLE IF NOT EXISTS app_role (
    code        VARCHAR(32)  NOT NULL PRIMARY KEY,
    name        VARCHAR(64)  NOT NULL,
    description VARCHAR(255) NULL,
    builtin     TINYINT      NOT NULL DEFAULT 1,
    seeded      TINYINT      NOT NULL DEFAULT 0,
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'RBAC 角色';

CREATE TABLE IF NOT EXISTS app_role_permission (
    role_code       VARCHAR(32)  NOT NULL,
    permission_code VARCHAR(128) NOT NULL,
    PRIMARY KEY (role_code, permission_code),
    INDEX idx_role_permission_code (permission_code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '角色-权限映射';
