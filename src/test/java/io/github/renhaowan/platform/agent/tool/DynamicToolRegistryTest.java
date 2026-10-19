package io.github.renhaowan.platform.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

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

    private static ToolDefinition adminOnlyTool(String name, String description, String schema) {
        ToolDefinition definition = tool(name, description, schema, 0);
        definition.setRequiredRole("ADMIN");
        return definition;
    }

    @Test
    void refreshBuildsCallbacksAndConfirmFlags() {
        when(toolGateway.listTools()).thenReturn(List.of(
                tool("search_rooms", "查询可用会议室", SEARCH_SCHEMA, 0),
                tool("cancel_booking", "取消预订", CANCEL_SCHEMA, 1)));
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(toolGateway);

        var callbacks = registry.callbacks();

        assertThat(callbacks).hasSize(2);
        assertThat(callbacks.get(0).getToolDefinition().name()).isEqualTo("search_rooms");
        assertThat(callbacks.get(0).getToolDefinition().description()).isEqualTo("查询可用会议室");
        assertThat(callbacks.get(0).getToolDefinition().inputSchema()).contains("date");
        assertThat(registry.requireConfirm("search_rooms")).isFalse();
        assertThat(registry.requireConfirm("cancel_booking")).isTrue();
    }

    @Test
    void emptyToolListYieldsNoCallbacks() {
        when(toolGateway.listTools()).thenReturn(List.of());
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(toolGateway);

        assertThat(registry.callbacks()).isEmpty();
    }

    @Test
    void roleRequirementIsDisclosedInToolDescription() {
        when(toolGateway.listTools()).thenReturn(List.of(
                adminOnlyTool("cancel_booking", "取消指定预订", CANCEL_SCHEMA)));
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(toolGateway);

        // 无权工具照常下发，角色要求写进描述——模型据此提前判断，而非盲目尝试后被拒
        assertThat(registry.callbacks()).hasSize(1);
        assertThat(registry.callbacks().get(0).getToolDefinition().description())
                .isEqualTo("取消指定预订（此操作需要 ADMIN 权限）");
    }

    @Test
    void toolInvocationIsDelegatedToGateway() {
        when(toolGateway.listTools()).thenReturn(List.of(
                tool("search_rooms", "查询可用会议室", SEARCH_SCHEMA, 0)));
        when(toolGateway.callTool("search_rooms", "{\"date\":\"2026-10-01\"}", null))
                .thenReturn("{\"rooms\":3}");
        DynamicToolRegistry registry = new DynamicToolRegistryImpl(toolGateway);

        var callback = registry.callbacks().get(0);

        assertThat(callback.call("{\"date\":\"2026-10-01\"}")).isEqualTo("{\"rooms\":3}");
    }
}
