package io.github.renhaowan.platform.gateway.message.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.renhaowan.platform.gateway.message.JsonRpcRequest;
import io.github.renhaowan.platform.gateway.message.JsonRpcResponse;
import io.github.renhaowan.platform.gateway.message.MessageContext;
import io.github.renhaowan.platform.gateway.message.MessageHandler;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * MCP initialize：版本协商 + 能力声明。客户端请求的版本受支持则回显，否则回落默认版本。
 */
@Component
public class InitializeHandler implements MessageHandler {

    public static final String DEFAULT_PROTOCOL_VERSION = "2024-11-05";
    private static final Set<String> SUPPORTED = Set.of("2024-11-05", "2025-03-26", "2025-06-18");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String method() {
        return "initialize";
    }

    @Override
    public JsonRpcResponse handle(JsonRpcRequest request, MessageContext context) {
        String requested = null;
        if (request.params() != null && request.params().hasNonNull("protocolVersion")) {
            requested = request.params().get("protocolVersion").asText();
        }
        String version = requested != null && SUPPORTED.contains(requested)
                ? requested : DEFAULT_PROTOCOL_VERSION;

        ObjectNode result = MAPPER.createObjectNode();
        result.put("protocolVersion", version);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "agent-platform-gateway");
        serverInfo.put("version", "0.1.0");
        return JsonRpcResponse.success(request.id(), result);
    }
}
