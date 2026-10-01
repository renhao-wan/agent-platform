package io.github.renhaowan.platform.gateway.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.renhaowan.platform.gateway.tenant.entity.Tenant;
import io.github.renhaowan.platform.gateway.tenant.mapper.TenantMapper;
import io.github.renhaowan.platform.gateway.tenant.mapper.TenantMapper;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
class ApiKeyFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private TenantMapper tenantMapper;

    @MockitoBean
    private io.github.renhaowan.platform.gateway.registry.mapper.ToolDefinitionMapper toolDefinitionMapper;

    @Test
    void missingApiKeyRejectedWith401() throws Exception {
        mockMvc.perform(get("/demo-gateway/mcp/sse"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value(-32001))
                .andExpect(jsonPath("$.error.message").value("missing api key"));
    }

    @Test
    void invalidApiKeyRejectedWith401() throws Exception {
        when(tenantMapper.selectOne(any())).thenReturn(null);

        mockMvc.perform(get("/demo-gateway/mcp/sse").header("X-Api-Key", "not-exist-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.message").value("invalid api key"));
    }

    @Test
    void validApiKeyPassesAuthAndReachesSseEndpoint() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("demo");
        tenant.setApiKey("valid-key");
        tenant.setStatus(Tenant.STATUS_ENABLED);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);

        // 通过鉴权后到达 SSE 端点：返回 200（异步建立），而非 401
        mockMvc.perform(get("/demo-gateway/mcp/sse").header("X-Api-Key", "valid-key"))
                .andExpect(status().isOk());
    }

    @Test
    void adminTenantsOpenWithoutKey() throws Exception {
        // /admin/tenants 是租户自助开通入口，保持开放
        mockMvc.perform(get("/admin/tenants"))
                .andExpect(status().isOk());
    }

    @Test
    void adminToolEndpointsRequireApiKey() throws Exception {
        mockMvc.perform(get("/admin/tools").param("gatewayId", "gw1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminToolEndpointsPassWithValidKey() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("demo");
        tenant.setApiKey("valid-key");
        tenant.setStatus(Tenant.STATUS_ENABLED);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);

        mockMvc.perform(get("/admin/tools").param("gatewayId", "gw1").header("X-Api-Key", "valid-key"))
                .andExpect(status().isOk());
    }
}
