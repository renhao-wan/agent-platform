package io.github.renhaowan.platform.agent.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 鉴权范围、身份加载与 /admin 权限判定。
 * <p>JWT 只带 uid，角色与权限必须每次回库加载——这里钉住"停用即失效"和"权限不足 403"两条语义。
 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    private static final String SECRET = "unit-test-secret-0123456789abcdef0123456789abcdef";

    @Mock
    private RbacService rbacService;

    private final JwtService jwtService = new JwtService(SECRET);

    private JwtAuthFilter filter() {
        return new JwtAuthFilter(jwtService, rbacService);
    }

    @Test
    void onlyUserAndAdminEndpointsAreProtected() {
        assertThat(JwtAuthFilter.requiresAuth("/api/v1/chat")).isTrue();
        assertThat(JwtAuthFilter.requiresAuth("/api/v1/confirm")).isTrue();
        assertThat(JwtAuthFilter.requiresAuth("/api/v1/tools")).isTrue();
        assertThat(JwtAuthFilter.requiresAuth("/api/v1/sessions/sk-1/messages")).isTrue();
        assertThat(JwtAuthFilter.requiresAuth("/admin/rbac/roles")).isTrue();
        assertThat(JwtAuthFilter.requiresAuth("/api/v1/auth/login")).isFalse();
        assertThat(JwtAuthFilter.requiresAuth("/")).isFalse();
    }

    @Test
    void adminUrisMapToSpecificPermissionCodes() {
        assertThat(JwtAuthFilter.requiredAdminPermission("/admin/rbac/roles"))
                .isEqualTo(RbacService.ADMIN_RBAC);
        assertThat(JwtAuthFilter.requiredAdminPermission("/admin/knowledge/jobs"))
                .isEqualTo(RbacService.ADMIN_KNOWLEDGE);
        assertThat(JwtAuthFilter.requiredAdminPermission("/admin/sources/sync"))
                .isEqualTo(RbacService.ADMIN_SOURCE);
        assertThat(JwtAuthFilter.requiredAdminPermission("/admin/tools"))
                .isEqualTo(RbacService.ADMIN_TOOL);
        assertThat(JwtAuthFilter.requiredAdminPermission("/admin/console"))
                .isEqualTo(RbacService.ADMIN_CONSOLE);
    }

    @Test
    void loginEndpointPassesThroughWithoutLoadingPrincipal() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(rbacService, never()).loadPrincipal(anyLong());
    }

    @Test
    void missingTokenIsRejectedWith401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/tools");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("missing bearer token");
    }

    @Test
    void disabledAccountIsRejectedEvenWithAValidToken() throws Exception {
        when(rbacService.loadPrincipal(9L)).thenReturn(Optional.empty());

        MockHttpServletRequest request = authorizedRequest("/api/v1/tools", 9L);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter().doFilter(request, response, mock(FilterChain.class));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("account disabled or removed");
    }

    @Test
    void adminEndpointRequiresItsPermissionCode() throws Exception {
        when(rbacService.loadPrincipal(1L)).thenReturn(Optional.of(
                new UserPrincipal(1L, "ADMIN", Set.of(RbacService.ADMIN_RBAC))));

        MockHttpServletRequest request = authorizedRequest("GET", "/admin/tools", 1L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).contains("admin:tool");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void permittedAdminPassesAndPrincipalIsVisibleInsideTheChain() throws Exception {
        UserPrincipal principal = new UserPrincipal(1L, "ADMIN", Set.of("*"));
        when(rbacService.loadPrincipal(1L)).thenReturn(Optional.of(principal));

        MockHttpServletRequest request = authorizedRequest("GET", "/admin/knowledge/jobs", 1L);
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(invocation -> {
            assertThat(UserContext.get()).isEqualTo(principal);
            return null;
        }).when(chain).doFilter(request, response);

        filter().doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        // 请求结束必须清理 ThreadLocal，避免线程复用时串号
        assertThat(UserContext.get()).isNull();
    }

    private MockHttpServletRequest authorizedRequest(String method, String uri, long uid) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.addHeader(JwtAuthFilter.HEADER_AUTHORIZATION,
                JwtAuthFilter.BEARER_PREFIX + jwtService.issue(uid));
        return request;
    }

    private MockHttpServletRequest authorizedRequest(String uri, long uid) {
        return authorizedRequest("GET", uri, uid);
    }
}
