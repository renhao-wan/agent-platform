package io.github.renhaowan.platform.agent.auth;

/**
 * 请求级用户上下文（JwtAuthFilter 写入，请求结束清理），模式与 TenantContext 一致。
 * <p>注意：ThreadLocal 不跨线程池传递——进入 agent-loop 线程池前必须在 HTTP 线程
 * 把 {@link UserPrincipal} 显式取值传参（见 ChatController）。
 */
public final class UserContext {

    private static final ThreadLocal<UserPrincipal> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(UserPrincipal principal) {
        HOLDER.set(principal);
    }

    public static UserPrincipal get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
