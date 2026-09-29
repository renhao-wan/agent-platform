package io.github.renhaowan.platform.gateway.security;

import io.github.renhaowan.platform.gateway.tenant.TenantService;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
}
