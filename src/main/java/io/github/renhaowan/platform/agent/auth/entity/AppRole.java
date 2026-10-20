package io.github.renhaowan.platform.agent.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * RBAC 角色。当前是单角色模型：{@code app_user.role} 直接指向本表 code。
 * <p>{@code seeded} 只用于"首次初始化默认权限"，人工编辑角色权限后不会被重启覆盖。
 */
@Data
@TableName("app_role")
public class AppRole {

    @TableId(type = IdType.INPUT)
    private String code;

    private String name;

    private String description;

    /** 1 = 平台内置角色（当前不做角色 CRUD，内置角色只读） */
    private Integer builtin;

    /** 1 = 默认权限已经初始化过；管理员后续的编辑不重置 */
    private Integer seeded;

    private LocalDateTime createdAt;
}
