package io.github.renhaowan.platform.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.renhaowan.platform.agent.auth.JwtService;
import io.github.renhaowan.platform.agent.auth.RbacService;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 工具目录端点（HTTP 层）：鉴权范围，以及服务凭证不出边界。
 */
@SpringBootTest(properties = {
        "spring.sql.init.mode=never",
        "platform.rbac.seed-on-startup=false"
})
@AutoConfigureMockMvc
class ToolCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @MockitoBean
    private ToolGateway toolGateway;

    @MockitoBean
    private RbacService rbacService;

    @Test
    void toolsWithoutTokenRejectedWith401() throws Exception {
        mockMvc.perform(get("/api/v1/tools"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void toolsListIsServedToAuthenticatedUser() throws Exception {
        when(toolGateway.listTools())
                .thenReturn(List.of(tool("search_rooms", "查询指定时间段可用的会议室")));
        when(rbacService.loadPrincipal(7L)).thenReturn(Optional.of(new UserPrincipal(7L, "STAFF")));

        mockMvc.perform(get("/api/v1/tools")
                        .header("Authorization", "Bearer " + jwtService.issue(7L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("search_rooms"))
                .andExpect(jsonPath("$[0].description").value("查询指定时间段可用的会议室"));
    }

    @Test
    void toolCredentialsNeverLeaveTheBoundary() throws Exception {
        ToolDefinition withSecret = tool("cancel_booking", "取消指定预订");
        withSecret.setAuthHeaderName("X-Service-Key");
        withSecret.setAuthHeaderValue("super-secret-service-key");
        when(toolGateway.listTools()).thenReturn(List.of(withSecret));
        when(rbacService.loadPrincipal(1L)).thenReturn(Optional.of(new UserPrincipal(1L, "ADMIN")));

        String body = mockMvc.perform(get("/api/v1/tools")
                        .header("Authorization", "Bearer " + jwtService.issue(1L)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // 转发用的服务凭证属于工具层内部，绝不能顺着"给前端看的工具清单"漏出去
        assertThat(body).doesNotContain("super-secret-service-key").doesNotContain("X-Service-Key");
    }

    private ToolDefinition tool(String name, String description) {
        ToolDefinition tool = new ToolDefinition();
        tool.setName(name);
        tool.setDescription(description);
        return tool;
    }
}
