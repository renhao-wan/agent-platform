package io.github.renhaowan.platform.agent.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 平台用户（app_user）：三级权限闸门的身份源。
 * 密码只存 SHA-256 十六进制小写摘要；role 决定工具可见性与执行权限（ADMIN &gt; STAFF）。
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

    /** SHA-256 十六进制小写摘要（长度 64） */
    private String passwordHash;

    private String role;

    /** 1 启用 0 停用 */
    private Integer status;

    private LocalDateTime createdAt;
}
