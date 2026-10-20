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

    public boolean hasPermission(String permission) {
        return permission != null && (permissions.contains(RbacService.WILDCARD)
                || permissions.contains(permission));
    }
}
