package io.github.renhaowan.platform.gateway.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JSON-RPC 2.0 响应构造器（result / error 二选一）。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record JsonRpcResponse(String jsonrpc, Object id, JsonNode result, RpcError error) {

    public static JsonRpcResponse success(Object id, JsonNode result) {
        return new JsonRpcResponse(JsonRpcRequest.VERSION, id, result, null);
    }

    public static JsonRpcResponse failure(Object id, int code, String message) {
        return new JsonRpcResponse(JsonRpcRequest.VERSION, id, null, new RpcError(code, message, null));
    }

    public static JsonRpcResponse methodNotFound(Object id, String method) {
        return failure(id, -32601, "method not found: " + method);
    }

    public static JsonRpcResponse invalidParams(Object id, String message) {
        return failure(id, -32602, message);
    }

    public static JsonRpcResponse internalError(Object id, String message) {
        return failure(id, -32603, message);
    }

    public Map<String, Object> toEventPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jsonrpc", jsonrpc);
        payload.put("id", id);
        if (result != null) {
            payload.put("result", result);
        }
        if (error != null) {
            payload.put("error", error);
        }
        return payload;
    }

    public record RpcError(int code, String message, Object data) {
    }
}
