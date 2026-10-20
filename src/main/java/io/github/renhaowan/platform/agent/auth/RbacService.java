package io.github.renhaowan.platform.agent.auth;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 轻量 RBAC：用户单角色（app_user.role）+ 角色-权限映射（app_role_permission）。
 * <p>JWT 只证明身份（uid），角色与权限每次请求从库加载——停用、降权在下一个请求生效。
 * 权限码由平台内置（{@code admin:*}）与业务契约（{@code x-required-permission}）共同构成，
 * 平台只存储与比对，不解释业务语义。{@code *} 为通配权限。
 */
public interface RbacService {

    String WILDCARD = "*";

    String ADMIN_CONSOLE = "admin:console";
    String ADMIN_SOURCE = "admin:source";
    String ADMIN_TOOL = "admin:tool";
    String ADMIN_KNOWLEDGE = "admin:knowledge";
    String ADMIN_RBAC = "admin:rbac";

    /** 平台内置权限码（业务契约声明的权限从工具表动态汇总）。 */
    List<String> BUILTIN_PERMISSIONS = List.of(
            ADMIN_CONSOLE, ADMIN_SOURCE, ADMIN_TOOL, ADMIN_KNOWLEDGE, ADMIN_RBAC);

    /**
     * 按用户 id 加载当前身份与权限。用户不存在或已停用返回 empty（调用方翻译为 401）。
     */
    Optional<UserPrincipal> loadPrincipal(long uid);

    /** 角色当前拥有的权限码；角色不存在时为空集合。 */
    Set<String> permissionsOfRole(String role);

    /** 全部角色及其权限（管理端矩阵）。 */
    List<RoleView> listRoles();

    /** 可分配的权限码全集：内置 + 契约声明的权限码。 */
    List<String> knownPermissions();

    /**
     * 覆盖式更新某角色的权限集合。旧映射先删后插，整体在一个事务内。
     *
     * @throws IllegalArgumentException 角色不存在
     */
    void replacePermissions(String roleCode, Set<String> permissions);

    record RoleView(String code, String name, String description, boolean builtin, Set<String> permissions) {
    }
}
