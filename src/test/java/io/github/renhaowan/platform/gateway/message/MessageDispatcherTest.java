package io.github.renhaowan.platform.gateway.message;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.gateway.message.handler.InitializeHandler;
import io.github.renhaowan.platform.gateway.message.handler.PingHandler;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageDispatcherTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MessageContext CTX = new MessageContext("gw", "session", 1L, "key");

    private final MessageDispatcher dispatcher =
            new MessageDispatcher(List.of(new InitializeHandler(), new PingHandler()));

    @Test
    void initializeEchoesSupportedProtocolVersion() throws Exception {
        JsonRpcRequest request = MAPPER.readValue(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2025-03-26\"}}", JsonRpcRequest.class);

        JsonRpcResponse response = dispatcher.dispatch(request, CTX);

        assertThat(response.error()).isNull();
        assertThat(response.result().get("protocolVersion").asText()).isEqualTo("2025-03-26");
        assertThat(response.result().get("capabilities").get("tools")).isNotNull();
    }

    @Test
    void initializeFallsBackOnUnknownVersion() throws Exception {
        JsonRpcRequest request = MAPPER.readValue(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"1999-01-01\"}}", JsonRpcRequest.class);

        JsonRpcResponse response = dispatcher.dispatch(request, CTX);

        assertThat(response.result().get("protocolVersion").asText())
                .isEqualTo(InitializeHandler.DEFAULT_PROTOCOL_VERSION);
    }

    @Test
    void pingReturnsEmptyResult() throws Exception {
        JsonRpcRequest request = MAPPER.readValue(
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"ping\"}", JsonRpcRequest.class);

        JsonRpcResponse response = dispatcher.dispatch(request, CTX);

        assertThat(response.error()).isNull();
        assertThat(response.result().isEmpty()).isTrue();
    }

    @Test
    void unknownMethodReturns32601() throws Exception {
        JsonRpcRequest request = MAPPER.readValue(
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"resources/list\"}", JsonRpcRequest.class);

        JsonRpcResponse response = dispatcher.dispatch(request, CTX);

        assertThat(response.error().code()).isEqualTo(-32601);
    }

    @Test
    void notificationWithoutIdProducesNoResponse() throws Exception {
        JsonRpcRequest request = MAPPER.readValue(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", JsonRpcRequest.class);

        assertThat(dispatcher.dispatch(request, CTX)).isNull();
    }
}
