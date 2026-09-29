package io.github.renhaowan.platform.gateway.security;

/**
 * 请求级租户上下文（鉴权过滤器写入，请求结束清理）。
 */
public final class TenantContext {

    public record TenantInfo(Long id, String name, String apiKey) {
    }

    private static final ThreadLocal<TenantInfo> HOLDER = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(TenantInfo info) {
        HOLDER.set(info);
    }

    public static TenantInfo get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
