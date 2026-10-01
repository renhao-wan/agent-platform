package io.github.renhaowan.platform.gateway.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.renhaowan.platform.gateway.session.GatewaySessionService;
import io.github.renhaowan.platform.gateway.tenant.entity.Tenant;
import io.github.renhaowan.platform.gateway.tenant.mapper.TenantMapper;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest(properties = "spring.sql.init.mode=never")
@AutoConfigureMockMvc
class StreamableGatewayControllerTest {

    private static final String KEY = "stream-key";
    private static final String SESSION = "11111111-2222-3333-4444-555555555555";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RedissonClient redissonClient;

    @MockitoBean
    private TenantMapper tenantMapper;

    @MockitoBean
    private GatewaySessionService sessionService;

    private void tenantAndSessionValid() {
        Tenant tenant = new Tenant();
        tenant.setId(9L);
        tenant.setName("demo");
        tenant.setApiKey(KEY);
        tenant.setStatus(Tenant.STATUS_ENABLED);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(sessionService.validate(SESSION)).thenReturn(Optional.of(
                new GatewaySessionService.SessionState(SESSION, 9L, "gw1", "STREAMABLE", "local-1", 1L)));
    }

    @Test
    void initializeCreatesSessionAndReturnsHeader() throws Exception {
        tenantAndSessionValid();
        when(sessionService.create(any(), any(), any())).thenReturn(
                new GatewaySessionService.SessionState(SESSION, 9L, "gw1", "STREAMABLE", "local-1", 1L));

        mockMvc.perform(post("/gw1/mcp").header("X-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                                + "\"params\":{\"protocolVersion\":\"2025-06-18\"}}"))
                .andExpect(status().isOk())
                .andExpect(header().string(StreamableGatewayController.SESSION_HEADER, SESSION))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"));
    }

    @Test
    void messageWithoutSessionHeaderRejected() throws Exception {
        tenantAndSessionValid();

        mockMvc.perform(post("/gw1/mcp").header("X-Api-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void messageOnValidSessionReturnsJson() throws Exception {
        tenantAndSessionValid();

        mockMvc.perform(post("/gw1/mcp").header("X-Api-Key", KEY)
                        .header(StreamableGatewayController.SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isMap());
    }

    @Test
    void messageOnUnknownSessionRejected() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(9L);
        tenant.setApiKey(KEY);
        tenant.setStatus(Tenant.STATUS_ENABLED);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(sessionService.validate("dead-session")).thenReturn(Optional.empty());

        mockMvc.perform(post("/gw1/mcp").header("X-Api-Key", KEY)
                        .header(StreamableGatewayController.SESSION_HEADER, "dead-session")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void notificationAcceptedWithoutResponseBody() throws Exception {
        tenantAndSessionValid();

        MvcResult result = mockMvc.perform(post("/gw1/mcp").header("X-Api-Key", KEY)
                        .header(StreamableGatewayController.SESSION_HEADER, SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void deleteTerminatesSession() throws Exception {
        tenantAndSessionValid();

        mockMvc.perform(delete("/gw1/mcp").header("X-Api-Key", KEY)
                        .header(StreamableGatewayController.SESSION_HEADER, SESSION))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteUnknownSessionReturns404() throws Exception {
        Tenant tenant = new Tenant();
        tenant.setId(9L);
        tenant.setApiKey(KEY);
        tenant.setStatus(Tenant.STATUS_ENABLED);
        when(tenantMapper.selectOne(any())).thenReturn(tenant);
        when(sessionService.validate("dead-session")).thenReturn(Optional.empty());

        mockMvc.perform(delete("/gw1/mcp").header("X-Api-Key", KEY)
                        .header(StreamableGatewayController.SESSION_HEADER, "dead-session"))
                .andExpect(status().isNotFound());
    }
}
