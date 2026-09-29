package io.github.renhaowan.platform.agent.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.agent.memory.AgentSessionService;
import io.github.renhaowan.platform.agent.loop.AgentRunner;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话入口：POST /api/v1/chat（SSE 流式返回）。
 * 循环在独立线程池执行（不占用容器业务线程），confirm-timeout 内连接保活由心跳兜底。
 */
@RestController
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    public record ChatRequest(String sessionKey, @NotBlank String message) {
    }

    private final AgentRunner agentRunner;
    private final AgentSessionService sessionService;
    private final ObjectMapper objectMapper;
    private final ExecutorService executor;

    public ChatController(AgentRunner agentRunner, AgentSessionService sessionService,
                          ObjectMapper objectMapper) {
        this.agentRunner = agentRunner;
        this.sessionService = sessionService;
        this.objectMapper = objectMapper;
        AtomicInteger seq = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(8, r -> {
            Thread thread = new Thread(r, "agent-loop-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    @PostMapping(path = "/api/v1/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody @Valid ChatRequest request) {
        SseEmitter emitter = new SseEmitter(15 * 60_000L);
        AgentEventEmitter events = new AgentEventEmitter(emitter, objectMapper);

        String sessionKey = request.sessionKey() == null || request.sessionKey().isBlank()
                ? sessionService.createSession(request.message().substring(0, Math.min(20, request.message().length())))
                : request.sessionKey();
        events.session(sessionKey);

        try {
            executor.execute(() -> {
                try {
                    agentRunner.run(sessionKey, request.message(), events);
                    emitter.complete();
                } catch (Exception e) {
                    log.error("agent loop failed, session {}", sessionKey, e);
                    events.error("内部错误：" + e.getMessage());
                    events.done();
                    emitter.complete();
                }
            });
        } catch (RejectedExecutionException e) {
            events.error("系统繁忙，请稍后重试");
            events.done();
            emitter.complete();
        }
        return emitter;
    }
}
