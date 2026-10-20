package io.github.renhaowan.platform.agent.auth;

import java.util.Set;

/**
 * 已认证用户的身份与授权快照（由 RBAC 表在请求时加载，不由客户端 token 权威决定）：
 * uid = app_user.id，role = 单角色码，permissions = 该角色当前拥有的权限码集合。
 * <p>不可变值对象——跨线程传递（HTTP 线程 → agent-loop 线程池）安全，是权限闸门与
 * 工具过滤的唯一身份凭据。{@code *} 为通配权限。
 */
public record UserPrincipal(long uid, String role, Set<String> permissions) {

    public UserPrincipal {
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }

    /**
     * 旧角色语义的兼容构造：ADMIN 视为拥有全部权限，其他角色视为拥有 {@code role:<ROLE>}。
     * 供尚未回库加载权限的测试与旧契约（x-required-role）使用。
     */
    public UserPrincipal(long uid, String role) {
        this(uid, role, legacyPermissions(role));
    }

    public boolean hasPermission(String permission) {
        return permission != null && (permissions.contains(RbacService.WILDCARD)
                || permissions.contains(permission));
    }

    private static Set<String> legacyPermissions(String role) {
        if (role == null || role.isBlank()) {
            return Set.of();
        }
        return "ADMIN".equals(role) ? Set.of(RbacService.WILDCARD) : Set.of("role:" + role);
    }
}
