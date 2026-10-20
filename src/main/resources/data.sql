-- 种子数据（spring.sql.init 随 schema.sql 之后幂等执行：不存在才插入，重复启动不产生脏数据）
-- 密码为 PBKDF2-HMAC-SHA256 摘要（格式 pbkdf2$迭代次数$盐$摘要）：admin123 / staff123
INSERT INTO app_user (username, password_hash, role)
SELECT 'admin', 'pbkdf2$210000$k4qXz92OxljgfDqd9oDvhg==$jM4Skfi2GLyTL7++zGFTy3/XsHePsouva0OYMMC2gWg=', 'ADMIN'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'admin');

INSERT INTO app_user (username, password_hash, role)
SELECT 'staff01', 'pbkdf2$210000$Jg+arMOCHtnspKocYYQx4A==$BopiPxTxn5CARrI5UcOKMAsNtXMH6SgCdl9mrfpGF6U=', 'STAFF'
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_user WHERE username = 'staff01');

