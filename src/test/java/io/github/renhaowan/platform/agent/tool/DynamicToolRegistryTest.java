package io.github.renhaowan.platform.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.tool.impl.DynamicToolRegistryImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DynamicToolRegistryTest {

    @Mock
    private McpGatewayClient gatewayClient;

    private final ObjectMapper mapper = new ObjectMapper();

    private static final UserPrincipal STAFF = new UserPrincipal(1L, "STAFF");
    private static final UserPrincipal ADMIN = new UserPrincipal(2L, "ADMIN");

    private static final String STAFF_TOOLS_JSON = """
            [
              {"name":"search_rooms","description":"查询可用会议室",
               "inputSchema":{"type":"object","properties":{"date":{"type":"string"}}},
               "x-require-confirm":false}
            ]
            """;

    private static final String ADMIN_TOOLS_JSON = """
            [
              {"name":"search_rooms","description":"查询可用会议室",
               "inputSchema":{"type":"object","properties":{"date":{"type":"string"}}},
               "x-require-confirm":false},
              {"name":"cancel_booking","description":"取消预订",
               "inputSchema":{"type":"object","properties":{"id":{"type":"integer"}}},
               "x-require-confirm":true}
            ]
            """;

    @Test
    void refreshBuildsCallbacksAndConfirmFlags() throws Exception {
        when(gatewayClient.listTools(ADMIN)).thenReturn(mapper.readTree(ADMIN_TOOLS_JSON));
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(gatewayClient);

        var callbacks = registry.callbacks(ADMIN);

        assertThat(callbacks).hasSize(2);
        assertThat(callbacks.get(0).getToolDefinition().name()).isEqualTo("search_rooms");
        assertThat(callbacks.get(0).getToolDefinition().description()).isEqualTo("查询可用会议室");
        assertThat(callbacks.get(0).getToolDefinition().inputSchema()).contains("date");
        assertThat(registry.requireConfirm("search_rooms", ADMIN)).isFalse();
        assertThat(registry.requireConfirm("cancel_booking", ADMIN)).isTrue();
    }

    @Test
    void emptyToolListYieldsNoCallbacks() {
        when(gatewayClient.listTools(STAFF)).thenReturn(mapper.createArrayNode());
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(gatewayClient);

        assertThat(registry.callbacks(STAFF)).isEmpty();
    }

    @Test
    void toolsAreCachedPerRole() throws Exception {
        // STAFF 的清单不含 ADMIN 工具，ADMIN 的含——注册表按角色分桶，互不串
        when(gatewayClient.listTools(STAFF)).thenReturn(mapper.readTree(STAFF_TOOLS_JSON));
        when(gatewayClient.listTools(ADMIN)).thenReturn(mapper.readTree(ADMIN_TOOLS_JSON));
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(gatewayClient);

        assertThat(registry.callbacks(STAFF)).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("search_rooms");
        assertThat(registry.callbacks(ADMIN)).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("search_rooms", "cancel_booking");
        // 敏感标记同样分桶：cancel_booking 仅在 ADMIN 桶内有定义
        assertThat(registry.requireConfirm("cancel_booking", STAFF)).isFalse();
        assertThat(registry.requireConfirm("cancel_booking", ADMIN)).isTrue();
    }
}
