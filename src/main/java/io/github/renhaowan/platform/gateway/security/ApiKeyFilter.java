package io.github.renhaowan.platform.gateway.security;

import io.github.renhaowan.platform.gateway.message.McpProtocol;
import io.github.renhaowan.platform.gateway.support.JsonRpcErrorWriter;
import io.github.renhaowan.platform.gateway.tenant.entity.Tenant;
import io.github.renhaowan.platform.gateway.tenant.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MCP 端点租户鉴权：X-Api-Key → 租户校验 → 限流 → 写入 TenantContext。
 * 覆盖 /{gatewayId}/mcp 与 /{gatewayId}/mcp/message；/admin/** 与 /ping 不在鉴权范围。
 */
@RequiredArgsConstructor
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = McpProtocol.HEADER_API_KEY;
    private static final Pattern MCP_PATH = Pattern.compile("^/[^/]+/mcp(/.*)?$");

    private final TenantService tenantService;
    private final RateLimitService rateLimitService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!requiresAuth(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            JsonRpcErrorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    McpProtocol.ERROR_UNAUTHORIZED, "missing api key");
            return;
        }

        Tenant tenant = tenantService.findByApiKey(apiKey)
                .filter(t -> t.getStatus() != null && t.getStatus() == Tenant.STATUS_ENABLED)
                .orElse(null);
        if (tenant == null) {
            JsonRpcErrorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    McpProtocol.ERROR_UNAUTHORIZED, "invalid api key");
            return;
        }

        if (!rateLimitService.tryAcquire(apiKey)) {
            JsonRpcErrorWriter.write(response, 429, McpProtocol.ERROR_RATE_LIMITED, "rate limit exceeded");
            return;
        }

        TenantContext.set(new TenantContext.TenantInfo(tenant.getId(), tenant.getName(), apiKey));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * 鉴权范围：MCP 端点（/{gw}/mcp/**）与租户级管理端点（/admin/tools、/admin/protocols）。
     * /admin/tenants 是租户自助开通入口，保持开放。
     */
    static boolean requiresAuth(String uri) {
        if (MCP_PATH.matcher(uri).matches()) {
            return true;
        }
        return uri.startsWith("/admin/") && !uri.startsWith("/admin/tenants");
    }
}
