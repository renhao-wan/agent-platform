package io.github.renhaowan.platform.gateway.message;

/**
 * MCP method 处理器契约（直排分发，新增 method 增加一个实现类即可）。
 */
public interface MessageHandler {

    String method();

    JsonRpcResponse handle(JsonRpcRequest request, MessageContext context);
}
