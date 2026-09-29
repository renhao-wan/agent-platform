package io.github.renhaowan.platform.gateway.message;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * JSON-RPC method 直排分发器。id 为空视为通知（notification），不产生响应。
 */
@Component
public class MessageDispatcher {

    private final Map<String, MessageHandler> handlers;

    public MessageDispatcher(List<MessageHandler> handlerList) {
        this.handlers = handlerList.stream()
                .collect(Collectors.toMap(MessageHandler::method, Function.identity()));
    }

    /** 返回 null 表示通知无需响应。 */
    public JsonRpcResponse dispatch(JsonRpcRequest request, MessageContext context) {
        if (request.id() == null) {
            return null;
        }
        MessageHandler handler = handlers.get(request.method());
        if (handler == null) {
            return JsonRpcResponse.methodNotFound(request.id(), request.method());
        }
        try {
            return handler.handle(request, context);
        } catch (Exception e) {
            return JsonRpcResponse.internalError(request.id(), e.getMessage());
        }
    }
}
