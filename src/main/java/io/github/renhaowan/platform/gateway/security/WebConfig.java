package io.github.renhaowan.platform.gateway.security;

import io.github.renhaowan.platform.agent.auth.JwtAuthFilter;
import io.github.renhaowan.platform.agent.auth.JwtService;
import io.github.renhaowan.platform.gateway.tenant.TenantService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet 过滤器装配：
 * order=1 ApiKeyFilter（MCP 端点租户鉴权）→ order=2 JwtAuthFilter（用户端点 JWT 鉴权）。
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<ApiKeyFilter> apiKeyFilter(TenantService tenantService,
                                                             RateLimitService rateLimitService) {
        FilterRegistrationBean<ApiKeyFilter> registration =
                new FilterRegistrationBean<>(new ApiKeyFilter(tenantService, rateLimitService));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(JwtService jwtService) {
        FilterRegistrationBean<JwtAuthFilter> registration =
                new FilterRegistrationBean<>(new JwtAuthFilter(jwtService));
        registration.addUrlPatterns("/*");
        registration.setOrder(2);
        return registration;
    }
}
