package io.github.renhaowan.platform.gateway.message.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.gateway.forward.GenericHttpForwarder;
import io.github.renhaowan.platform.gateway.message.JsonRpcRequest;
import io.github.renhaowan.platform.gateway.message.JsonRpcResponse;
import io.github.renhaowan.platform.gateway.message.MessageContext;
import io.github.renhaowan.platform.gateway.registry.ToolDefinition;
import io.github.renhaowan.platform.gateway.registry.ToolRegistryService;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ToolsHandlersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageContext CTX = new MessageContext("gw1", "s", 7L, "key");

    @Mock
    private ToolRegistryService registryService;

    @Mock
    private GenericHttpForwarder forwarder;

    private JsonRpcRequest request(String json) throws Exception {
        return MAPPER.readValue(json, JsonRpcRequest.class);
    }

    @Test
    void toolsListReturnsEnabledToolsWithSchema() throws Exception {
        ToolDefinition tool = new ToolDefinition();
        tool.setName("search_rooms");
        tool.setDescription("查询可用会议室");
        tool.setInputSchema("{\"type\":\"object\",\"properties\":{\"date\":{\"type\":\"string\"}}}");
        when(registryService.listEnabled(7L, "gw1")).thenReturn(List.of(tool));

        JsonRpcResponse response = new ToolsListHandler(registryService)
                .handle(request("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"), CTX);

        assertThat(response.error()).isNull();
        assertThat(response.result().get("tools").size()).isEqualTo(1);
        assertThat(response.result().get("tools").get(0).get("name").asText()).isEqualTo("search_rooms");
        assertThat(response.result().get("tools").get(0).get("inputSchema").get("properties")).isNotNull();
    }

    @Test
    void toolsCallUnknownToolReturnsInvalidParams() throws Exception {
        when(registryService.find(7L, "gw1", "nope")).thenReturn(Optional.empty());

        JsonRpcResponse response = new ToolsCallHandler(registryService, forwarder)
                .handle(request("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"nope\",\"arguments\":{}}}"), CTX);

        assertThat(response.error().code()).isEqualTo(-32602);
        assertThat(response.error().message()).contains("unknown tool");
    }

    @Test
    void toolsCallWrapsBackendBodyAsTextContent() throws Exception {
        ToolDefinition tool = new ToolDefinition();
        tool.setName("search_rooms");
        when(registryService.find(eq(7L), eq("gw1"), eq("search_rooms"))).thenReturn(Optional.of(tool));
        when(forwarder.forward(any(ToolDefinition.class), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(true, 200, "{\"rooms\":3}", null));

        JsonRpcResponse response = new ToolsCallHandler(registryService, forwarder)
                .handle(request("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"search_rooms\",\"arguments\":{\"date\":\"2026-10-01\"}}}"), CTX);

        assertThat(response.error()).isNull();
        assertThat(response.result().get("isError").asBoolean()).isFalse();
        assertThat(response.result().get("content").get(0).get("text").asText()).contains("\"rooms\":3");
    }

    @Test
    void toolsCallBackendFailureIsErrorResultNotProtocolError() throws Exception {
        ToolDefinition tool = new ToolDefinition();
        tool.setName("create_booking");
        when(registryService.find(eq(7L), eq("gw1"), eq("create_booking"))).thenReturn(Optional.of(tool));
        when(forwarder.forward(any(ToolDefinition.class), any()))
                .thenReturn(new GenericHttpForwarder.ForwardResult(false, 500, "", "backend responded 500"));

        JsonRpcResponse response = new ToolsCallHandler(registryService, forwarder)
                .handle(request("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
                        + "\"params\":{\"name\":\"create_booking\",\"arguments\":{}}}"), CTX);

        assertThat(response.error()).isNull();
        assertThat(response.result().get("isError").asBoolean()).isTrue();
        assertThat(response.result().get("content").get(0).get("text").asText()).contains("500");
    }
}
