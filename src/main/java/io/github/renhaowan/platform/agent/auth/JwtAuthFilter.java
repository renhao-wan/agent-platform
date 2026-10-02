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
 * 拦 {@code /api/v1/chat} 与 {@code /api/v1/confirm}，校验 {@code Authorization: Bearer <JWT>}，
 * 解出 uid/role 写入 {@link UserContext}（请求结束清理）；其余路径一律放行。
 * 401 直接在过滤器层输出 JSON（尚未进入 @ControllerAdvice 范围）。
 */
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String HEADER_AUTHORIZATION = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

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

        UserContext.set(principal);
        try {
            chain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    /** 鉴权范围：对话与确认两个用户端点（登录/MCP/管理端不在其列）。 */
    static boolean requiresAuth(String uri) {
        return "/api/v1/chat".equals(uri) || "/api/v1/confirm".equals(uri);
    }

    private void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 401);
        body.put("message", message);
        body.put("data", null);
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
