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
 * 与 {@code /admin/**}，
 * 校验 {@code Authorization: Bearer <JWT>}，解出 uid/role 写入 {@link UserContext}（请求结束清理）；
 * 其余路径一律放行。{@code /admin/**} 额外要求 ADMIN 角色。
 * 401/403 直接在过滤器层输出 JSON（尚未进入 @ControllerAdvice 范围）。
 */
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";
    public static final String ADMIN_ROLE = "ADMIN";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JwtService jwtService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!requiresAuth(request.getRequestURI())) {
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
            principal = jwtService.parse(header.substring(BEARER_PREFIX.length()).trim());
        } catch (JwtException | IllegalArgumentException e) {
            unauthorized(response, "invalid or expired token");
            return;
        }

        if (isAdminOnly(request.getRequestURI()) && !ADMIN_ROLE.equals(principal.role())) {
            forbidden(response, "需要 ADMIN 角色");
            return;
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
        return "/api/v1/chat".equals(uri) || "/api/v1/confirm".equals(uri) || "/api/v1/tools".equals(uri)
                || uri.startsWith("/api/v1/sessions") || uri.startsWith("/admin/");
    }

    /** 管理端：协议导入与工具查询，仅 ADMIN。 */
    static boolean isAdminOnly(String uri) {
        return uri.startsWith("/admin/");
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
