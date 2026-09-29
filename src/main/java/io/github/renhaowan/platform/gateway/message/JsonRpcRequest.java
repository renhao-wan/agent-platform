package io.github.renhaowan.platform.gateway.message;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * MCP 消息（JSON-RPC 2.0）统一信封。
 */
public record JsonRpcRequest(String jsonrpc, Object id, String method, JsonNode params) {

    public static final String VERSION = "2.0";
}
