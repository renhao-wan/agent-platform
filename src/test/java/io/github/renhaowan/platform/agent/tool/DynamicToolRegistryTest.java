package io.github.renhaowan.platform.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import io.github.renhaowan.platform.agent.auth.UserPrincipal;
import io.github.renhaowan.platform.agent.tool.impl.DynamicToolRegistryImpl;
import io.github.renhaowan.platform.gateway.registry.entity.ToolDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DynamicToolRegistryTest {

    @Mock
    private ToolGateway toolGateway;

    private static final UserPrincipal STAFF = new UserPrincipal(1L, "STAFF");
    private static final UserPrincipal ADMIN = new UserPrincipal(2L, "ADMIN");

    private static final String SEARCH_SCHEMA = "{\"type\":\"object\",\"properties\":{\"date\":{\"type\":\"string\"}}}";
    private static final String CANCEL_SCHEMA = "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}}}";

    private static ToolDefinition tool(String name, String description, String schema, int requireConfirm) {
        ToolDefinition definition = new ToolDefinition();
        definition.setName(name);
        definition.setDescription(description);
        definition.setInputSchema(schema);
        definition.setRequireConfirm(requireConfirm);
        definition.setEnabled(1);
        return definition;
    }

    private static final List<ToolDefinition> STAFF_TOOLS = List.of(
            tool("search_rooms", "查询可用会议室", SEARCH_SCHEMA, 0));

    private static final List<ToolDefinition> ADMIN_TOOLS = List.of(
            tool("search_rooms", "查询可用会议室", SEARCH_SCHEMA, 0),
            tool("cancel_booking", "取消预订", CANCEL_SCHEMA, 1));

    @Test
    void refreshBuildsCallbacksAndConfirmFlags() {
        when(toolGateway.listTools(ADMIN)).thenReturn(ADMIN_TOOLS);
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(toolGateway);

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
        when(toolGateway.listTools(STAFF)).thenReturn(List.of());
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(toolGateway);

        assertThat(registry.callbacks(STAFF)).isEmpty();
    }

    @Test
    void toolsAreCachedPerRole() {
        // STAFF 的清单不含 ADMIN 工具，ADMIN 的含——注册表按角色分桶，互不串
        when(toolGateway.listTools(STAFF)).thenReturn(STAFF_TOOLS);
        when(toolGateway.listTools(ADMIN)).thenReturn(ADMIN_TOOLS);
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(toolGateway);

        assertThat(registry.callbacks(STAFF)).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("search_rooms");
        assertThat(registry.callbacks(ADMIN)).extracting(cb -> cb.getToolDefinition().name())
                .containsExactly("search_rooms", "cancel_booking");
        // 敏感标记同样分桶：cancel_booking 仅在 ADMIN 桶内有定义
        assertThat(registry.requireConfirm("cancel_booking", STAFF)).isFalse();
        assertThat(registry.requireConfirm("cancel_booking", ADMIN)).isTrue();
    }

    @Test
    void toolInvocationIsDelegatedToGateway() {
        when(toolGateway.listTools(ADMIN)).thenReturn(ADMIN_TOOLS);
        when(toolGateway.callTool("search_rooms", "{\"date\":\"2026-10-01\"}", null)).thenReturn("{\"rooms\":3}");
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(toolGateway);

        var callback = registry.callbacks(ADMIN).get(0);

        assertThat(callback.call("{\"date\":\"2026-10-01\"}")).isEqualTo("{\"rooms\":3}");
    }
}
