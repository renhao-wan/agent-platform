package io.github.renhaowan.platform.agent.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 角色-权限映射。权限码由平台内置（admin:*）或业务契约声明（x-required-permission），
 * 平台只存储与比对，不解释业务语义。
 */
@Data
@TableName("app_role_permission")
public class AppRolePermission {

    @TableId(type = IdType.INPUT)
    private String roleCode;

    private String permissionCode;
}
