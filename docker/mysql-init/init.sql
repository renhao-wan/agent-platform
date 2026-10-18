-- 开发环境建库（容器首次启动、数据卷为空时由 mysql 官方镜像执行）
-- agent_platform：平台自身 schema；meeting_booking：本地联调时业务单体的库
CREATE DATABASE IF NOT EXISTS agent_platform DEFAULT CHARSET utf8mb4;
CREATE DATABASE IF NOT EXISTS meeting_booking DEFAULT CHARSET utf8mb4;
