package io.github.renhaowan.platform.agent.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 类型化 SSE 事件发射器：事件名见 {@link EventType} 常量（intent/plan/tool_call/...）。
 * 前端按 event 名路由渲染；连接中断后静默丢弃后续事件（循环继续跑完并落库，前端重连可查历史）。
 */
@Slf4j
public class AgentEventEmitter {

    /** SSE 事件名约定（event: 字段取值），与前端 static/index.html 及压测脚本保持一致 */
    public static final class EventType {
        public static final String SESSION = "session";
        public static final String INTENT = "intent";
        public static final String PLAN = "plan";
        public static final String TOOL_CALL = "tool_call";
        public static final String TOOL_RESULT = "tool_result";
        public static final String CONFIRM_REQUEST = "confirm_request";
        public static final String ANSWER = "answer";
        public static final String ERROR = "error";
        public static final String DONE = "done";

        private EventType() {
        }
    }

    private final SseEmitter emitter;
    private final ObjectMapper mapper;
    private volatile boolean closed = false;

    public AgentEventEmitter(SseEmitter emitter, ObjectMapper mapper) {
        this.emitter = emitter;
        this.mapper = mapper;
    }

    public synchronized void send(String event, Object data) {
        if (closed) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data)));
        } catch (Exception e) {
            closed = true;
            log.warn("sse emit failed ({}), drop following events: {}", event, e.getMessage());
        }
    }

    public void session(String sessionKey) {
        send(EventType.SESSION, Map.of("sessionKey", sessionKey));
    }


    public void plan(List<String> steps) {
        send(EventType.PLAN, Map.of("steps", steps));
    }

    public void toolCall(String tool, String argumentsJson) {
        send(EventType.TOOL_CALL, Map.of("tool", tool, "arguments", argumentsJson));
    }

    public void toolResult(String tool, String summary) {
        send(EventType.TOOL_RESULT, Map.of("tool", tool, "summary", summary));
    }

    /** 敏感操作确认请求：前端渲染确认按钮，用户点击后经 ConfirmController 恢复挂起的循环。 */
    public void confirmRequest(String token, String message) {
        send(EventType.CONFIRM_REQUEST, Map.of("confirmToken", token, "message", message));
    }

    public void answer(String content) {
        send(EventType.ANSWER, Map.of("content", content));
    }

    public void error(String message) {
        send(EventType.ERROR, Map.of("message", message));
    }

    public void done() {
        send(EventType.DONE, Map.of());
    }
}
