package io.github.renhaowan.platform.agent.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 用户 JWT 鉴权过滤器（手写，不引入 Spring Security）：
 * 拦 {@code /api/v1/chat}、{@code /api/v1/confirm}、{@code /api/v1/tools}、{@code /api/v1/sessions/**}
 * 与 {@code /admin/**}，校验 {@code Authorization: Bearer <JWT>}。
 * <p>JWT 只证明身份（uid）；过滤器每次请求回库加载用户状态、角色与角色权限，
 * 构造 {@link UserPrincipal} 写入 {@link UserContext}（请求结束清理）。因此停用/降权在
 * 下一个请求即生效，不需要 token 撤销机制。
 * <p>{@code /admin/**} 按 URI 映射到管理权限码，要求对应权限。401/403 直接在过滤器层
 * 输出 JSON（尚未进入 @ControllerAdvice 范围）。
 */
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtService jwtService;
    private final RbacService rbacService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String uri = request.getRequestURI();
        if (!requiresAuth(uri)) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(HEADER_AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            unauthorized(response, "missing bearer token");
            return;
        }
        UserPrincipal principal;
        try {
            long uid = jwtService.parseUid(header.substring(BEARER_PREFIX.length()).trim());
            principal = rbacService.loadPrincipal(uid).orElse(null);
        } catch (JwtException | IllegalArgumentException e) {
            unauthorized(response, "invalid or expired token");
            return;
        }
        if (principal == null) {
            unauthorized(response, "account disabled or removed");
            return;
        }

        if (uri.startsWith("/admin/")) {
            String required = requiredAdminPermission(uri);
            if (!principal.hasPermission(required)) {
                forbidden(response, "权限不足：需要 " + required + " 权限");
                return;
            }
        }

        UserContext.set(principal);
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    /** 鉴权范围：对话、确认、工具目录、历史会话四个用户端点 + 管理端（登录页自身不在其列）。 */
    static boolean requiresAuth(String uri) {
        // 先剥掉矩阵参数：/api/v1/chat;jsessionid=x 在容器里仍会路由到 chat，
        // 但与常量做 equals 就漏判了。鉴权判断必须落在容器实际路由的路径上。
        String path = stripMatrixParams(uri);
        return "/api/v1/chat".equals(path) || "/api/v1/confirm".equals(path) || "/api/v1/tools".equals(path)
                || path.startsWith("/api/v1/sessions") || path.startsWith("/admin/");
    }

    private static String stripMatrixParams(String uri) {
        int semicolon = uri.indexOf(';');
        return semicolon < 0 ? uri : uri.substring(0, semicolon);
    }

    /** 管理端 URI → 权限码。更具体的前缀优先，其余落到管理台入口权限。 */
    static String requiredAdminPermission(String uri) {
        if (uri.startsWith("/admin/rbac")) {
            return RbacService.ADMIN_RBAC;
        }
        if (uri.startsWith("/admin/knowledge")) {
            return RbacService.ADMIN_KNOWLEDGE;
        }
        if (uri.startsWith("/admin/sources")) {
            return RbacService.ADMIN_SOURCE;
        }
        if (uri.startsWith("/admin/tools")) {
            return RbacService.ADMIN_TOOL;
        }
        return RbacService.ADMIN_CONSOLE;
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, message);
    }

    private void forbidden(HttpServletResponse response, String message) throws IOException {
        writeError(response, HttpServletResponse.SC_FORBIDDEN, message);
    }

    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", status);
        body.put("message", message);
        body.put("data", null);
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
