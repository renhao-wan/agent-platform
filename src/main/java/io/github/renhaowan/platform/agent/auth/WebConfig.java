package io.github.renhaowan.platform.agent.auth;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Servlet 过滤器装配：唯一的鉴权入口是 JwtAuthFilter（JWT 身份 + 请求时加载的 RBAC 权限）。
 */
@Configuration
public class WebConfig {

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilter(JwtService jwtService, RbacService rbacService) {
        FilterRegistrationBean<JwtAuthFilter> registration =
                new FilterRegistrationBean<>(new JwtAuthFilter(jwtService, rbacService));
        registration.addUrlPatterns("/*");
        registration.setOrder(1);
        return registration;
    }
}
