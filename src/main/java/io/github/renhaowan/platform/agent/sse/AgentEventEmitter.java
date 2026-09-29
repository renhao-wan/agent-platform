package io.github.renhaowan.platform.agent.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 类型化 SSE 事件发射器：intent / plan / tool_call / tool_result / confirm_request / answer / error / done。
 * 连接中断后静默丢弃后续事件（循环继续跑完并落库，前端重连可查历史）。
 */
public class AgentEventEmitter {

    private static final Logger log = LoggerFactory.getLogger(AgentEventEmitter.class);

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
        send("session", Map.of("sessionKey", sessionKey));
    }

    public void intent(String label) {
        send("intent", Map.of("label", label));
    }

    public void plan(List<String> steps) {
        send("plan", Map.of("steps", steps));
    }

    public void toolCall(String tool, String argumentsJson) {
        send("tool_call", Map.of("tool", tool, "arguments", argumentsJson));
    }

    public void toolResult(String tool, String summary) {
        send("tool_result", Map.of("tool", tool, "summary", summary));
    }

    public void confirmRequest(String token, String message) {
        send("confirm_request", Map.of("confirmToken", token, "message", message));
    }

    public void answer(String content) {
        send("answer", Map.of("content", content));
    }

    public void error(String message) {
        send("error", Map.of("message", message));
    }

    public void done() {
        send("done", Map.of());
    }
}
