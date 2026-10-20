package io.github.renhaowan.platform.agent.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 类型化 SSE 事件发射器：事件名见 {@link EventType} 常量（session/plan/tool_call/tool_result/confirm_request/answer/error/done）。
 * 前端按 event 名路由渲染；连接中断后静默丢弃后续事件（循环继续跑完并落库，前端重连可查历史）。
 *
 * <p>空闲期每 15 秒发一条 SSE comment 保活：确认等待最长 5 分钟、模型单次生成最长 120 秒，
 * 期间没有任何业务事件；反向代理的 idle timeout（常见 60 秒）会把连接断开。
 * comment 不产生事件，浏览器的 EventSource 自动忽略。
 */
@Slf4j
public class AgentEventEmitter {

    private static final long DEFAULT_HEARTBEAT_MS = 15_000;

    /**
     * 所有连接共享一个调度器（守护线程）：每条连接各自开线程会让 40 并发变成 40 条常驻线程。
     * 两个线程是为了容忍个别连接写阻塞时其他连接仍能保活。
     */
    private static final ScheduledExecutorService HEARTBEATS =
            Executors.newScheduledThreadPool(2, r -> {
                Thread thread = new Thread(r, "sse-heartbeat");
                thread.setDaemon(true);
                return thread;
            });

    /** SSE 事件名约定（event: 字段取值），与前端 static/index.html 及压测脚本保持一致 */
    public static final class EventType {
        public static final String SESSION = "session";
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
    private final ScheduledFuture<?> heartbeatTask;
    private volatile boolean closed = false;

    public AgentEventEmitter(SseEmitter emitter, ObjectMapper mapper) {
        this(emitter, mapper, DEFAULT_HEARTBEAT_MS);
    }

    /** {@code heartbeatIntervalMs <= 0} 关闭保活（测试或不部署在代理后时可用）。 */
    public AgentEventEmitter(SseEmitter emitter, ObjectMapper mapper, long heartbeatIntervalMs) {
        this.emitter = emitter;
        this.mapper = mapper;
        this.heartbeatTask = heartbeatIntervalMs > 0
                ? HEARTBEATS.scheduleAtFixedRate(this::heartbeat,
                        heartbeatIntervalMs, heartbeatIntervalMs, TimeUnit.MILLISECONDS)
                : null;
    }

    /** 发送一条不产生事件的保活注释；写失败说明连接已断，停止后续发送。 */
    public synchronized void heartbeat() {
        if (closed) {
            cancelHeartbeat();
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("heartbeat"));
        } catch (Exception e) {
            closed = true;
            cancelHeartbeat();
            log.debug("sse heartbeat failed, stop emitting: {}", e.getMessage());
        }
    }

    private void cancelHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
        }
    }

    public synchronized void send(String event, Object data) {
        if (closed) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(mapper.writeValueAsString(data)));
        } catch (Exception e) {
            closed = true;
            cancelHeartbeat();
            log.warn("sse emit failed ({}), drop following events: {}", event, e.getMessage());
        }
    }

    /** 连接是否已断开；AgentRunner 据此停止循环、把待确认操作按拒绝处理。 */
    public boolean isClosed() {
        return closed;
    }

    /** ChatController 在 emitter 的完成/超时/错误回调里调用，主动标记连接结束。 */
    public void close() {
        closed = true;
        cancelHeartbeat();
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

    /** 正常跑完一轮时的统计：步数、耗时与 token，前端据此展示本轮成本。 */
    public void done(int steps, long elapsedMs, int promptTokens, int completionTokens) {
        send(EventType.DONE, Map.of(
                "steps", steps,
                "elapsedMs", elapsedMs,
                "promptTokens", promptTokens,
                "completionTokens", completionTokens,
                "totalTokens", promptTokens + completionTokens));
    }
}
