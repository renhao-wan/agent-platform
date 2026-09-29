package io.github.renhaowan.platform.gateway.message.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.renhaowan.platform.gateway.message.JsonRpcRequest;
import io.github.renhaowan.platform.gateway.message.JsonRpcResponse;
import io.github.renhaowan.platform.gateway.message.MessageContext;
import io.github.renhaowan.platform.gateway.message.MessageHandler;
import org.springframework.stereotype.Component;

/**
 * MCP ping：空结果应答，供客户端探活。
 */
@Component
public class PingHandler implements MessageHandler {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String method() {
        return "ping";
    }

    @Override
    public JsonRpcResponse handle(JsonRpcRequest request, MessageContext context) {
        ObjectNode result = MAPPER.createObjectNode();
        return JsonRpcResponse.success(request.id(), result);
    }
}
