package io.github.renhaowan.platform.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DynamicToolRegistryTest {

    @Mock
    private McpGatewayClient gatewayClient;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void refreshBuildsCallbacksAndConfirmFlags() throws Exception {
        String toolsJson = """
                [
                  {"name":"search_rooms","description":"查询可用会议室",
                   "inputSchema":{"type":"object","properties":{"date":{"type":"string"}}},
                   "x-require-confirm":false},
                  {"name":"cancel_booking","description":"取消预订",
                   "inputSchema":{"type":"object","properties":{"id":{"type":"integer"}}},
                   "x-require-confirm":true}
                ]
                """;
        when(gatewayClient.listTools()).thenReturn(mapper.readTree(toolsJson));
        DynamicToolRegistry registry = new DynamicToolRegistry(gatewayClient);

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
        when(gatewayClient.listTools()).thenReturn(mapper.createArrayNode());
        DynamicToolRegistry registry = new DynamicToolRegistry(gatewayClient);

        assertThat(registry.callbacks()).isEmpty();
    }
}
