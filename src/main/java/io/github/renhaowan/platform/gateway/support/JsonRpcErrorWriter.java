package io.github.renhaowan.platform.gateway.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 过滤器层直接输出 JSON-RPC 错误响应（进入消息分发前的鉴权/限流失败）。
 */
public final class JsonRpcErrorWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonRpcErrorWriter() {
    }

    public static void write(HttpServletResponse response, int httpStatus, int rpcCode, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", rpcCode);
        error.put("message", message);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", null);
        body.put("error", error);
        response.getWriter().write(MAPPER.writeValueAsString(body));
    }
}
