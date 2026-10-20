package io.github.renhaowan.platform.agent.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 平台用户（app_user）：三级权限闸门的身份源。
 * 密码存 PBKDF2 摘要，历史 SHA-256 摘要仅在登录时兼容校验并原地升级；
 * role 决定执行点权限（ADMIN &gt; STAFF）。
 */
@Data
@TableName("app_user")
public class AppUser {

    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_STAFF = "STAFF";
    public static final int STATUS_ENABLED = 1;

    @TableId(type = IdType.AUTO)
    private Long id;

    private String username;

    /** PBKDF2 摘要（pbkdf2$iter$salt$hash）；历史数据可能是 64 位 SHA-256 十六进制 */
    private String passwordHash;

    private String role;

    /** 1 启用 0 停用 */
    private Integer status;

    private LocalDateTime createdAt;
}
