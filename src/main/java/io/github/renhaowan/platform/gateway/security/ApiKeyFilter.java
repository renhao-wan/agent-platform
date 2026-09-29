package io.github.renhaowan.platform.gateway.security;

import io.github.renhaowan.platform.gateway.support.JsonRpcErrorWriter;
import io.github.renhaowan.platform.gateway.tenant.Tenant;
import io.github.renhaowan.platform.gateway.tenant.TenantService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * MCP 端点租户鉴权：X-Api-Key → 租户校验 → 限流 → 写入 TenantContext。
 * 覆盖 /{gatewayId}/mcp 与 /{gatewayId}/mcp/message；/admin/** 与 /ping 不在鉴权范围。
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-Api-Key";
    private static final Pattern MCP_PATH = Pattern.compile("^/[^/]+/mcp(/.*)?$");
    private static final int RPC_UNAUTHORIZED = -32001;
    private static final int RPC_RATE_LIMITED = -32002;

    private final TenantService tenantService;
    private final RateLimitService rateLimitService;

    public ApiKeyFilter(TenantService tenantService, RateLimitService rateLimitService) {
        this.tenantService = tenantService;
        this.rateLimitService = rateLimitService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!MCP_PATH.matcher(request.getRequestURI()).matches()) {
            chain.doFilter(request, response);
            return;
        }

        String apiKey = request.getHeader(API_KEY_HEADER);
        if (apiKey == null || apiKey.isBlank()) {
            JsonRpcErrorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    RPC_UNAUTHORIZED, "missing api key");
            return;
        }

        Tenant tenant = tenantService.findByApiKey(apiKey)
                .filter(t -> t.getStatus() != null && t.getStatus() == Tenant.STATUS_ENABLED)
                .orElse(null);
        if (tenant == null) {
            JsonRpcErrorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    RPC_UNAUTHORIZED, "invalid api key");
            return;
        }

        if (!rateLimitService.tryAcquire(apiKey)) {
            JsonRpcErrorWriter.write(response, 429, RPC_RATE_LIMITED, "rate limit exceeded");
            return;
        }

        TenantContext.set(new TenantContext.TenantInfo(tenant.getId(), tenant.getName(), apiKey));
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
