-- 种子数据（spring.sql.init 随 schema.sql 之后幂等执行：不存在才插入，重复启动不产生脏数据）
-- 密码为 SHA-256 十六进制小写：admin123 / staff123 的对应摘要见各行注释
INSERT INTO app_user (username, password_hash, role)
SELECT 'admin', '240be518fabd2724ddb6f04eeb1da5967448d7e831c08c8fa822809f74c720a9', 'ADMIN'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'admin');

INSERT INTO app_user (username, password_hash, role)
SELECT 'staff01', '10176e7b7b24d317acfcf8d2064cfd2f24e154f7b5a96603077d5ef813d6a6b6', 'STAFF'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'staff01');

