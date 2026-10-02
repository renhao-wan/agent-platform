package io.github.renhaowan.platform.agent.auth;

/**
 * 已认证用户身份（JWT claims 的领域投影）：uid = app_user.id，role = ADMIN | STAFF。
 * <p>不可变值对象——跨线程传递（HTTP 线程 → agent-loop 线程池）安全，是
 * 权限闸门与工具过滤的唯一身份凭据。
 */
public record UserPrincipal(long uid, String role) {
}
