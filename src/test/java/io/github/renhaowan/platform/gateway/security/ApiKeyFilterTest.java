package io.github.renhaowan.platform.gateway.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.renhaowan.platform.gateway.tenant.Tenant;
import io.github.renhaowan.platform.gateway.tenant.TenantMapper;
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
    void validApiKeyPassesAuthAndReachesRouting() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(1L);
        tenant.setName("demo");
        tenant.setApiKey("valid-key");
        tenant.setStatus(Tenant.STATUS_ENABLED);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);

        // M1.2 尚无 MCP 控制器：通过鉴权后表现为 404（路由不存在）而非 401
        mockMvc.perform(get("/demo-gateway/mcp/sse").header("X-Api-Key", "valid-key"))
                .andExpect(status().isNotFound());
    }

    @Test
    void adminEndpointsSkipApiKeyFilter() throws Exception {
        // /admin/** 不在鉴权范围：TenantAdminController 存在，返回 200 而非 401
        mockMvc.perform(get("/admin/tenants"))
                .andExpect(status().isOk());
    }
}
