package io.github.renhaowan.platform.agent.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.renhaowan.platform.agent.loop.AgentRunner;
import io.github.renhaowan.platform.agent.memory.AgentSessionService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 对话入口：POST /api/v1/chat（SSE 流式返回）。
 * ReAct 循环在独立线程池执行（不占用容器工作线程），confirm 等待期内连接保活由心跳兜底。
 *
 * <p>【面试点：为什么禁用 Executors 工厂方法，显式 new ThreadPoolExecutor？】
 * <ul>
 *   <li>Executors.newFixedThreadPool / newSingleThreadExecutor 内部是<b>无界</b> LinkedBlockingQueue——
 *       每个对话要占用线程几十秒（等待 LLM/确认），流量一涨任务在队列里无限堆积，直接 OOM；</li>
 *   <li>Executors.newCachedThreadPool 最大线程数是 Integer.MAX_VALUE——突发流量会创建海量线程，线程栈 OOM。</li>
 *   <li>显式构造把全部参数摆到明面上，容量上限、拒绝行为都可控、可解释、可压测。</li>
 * </ul>
 *
 * <p>【面试点：ThreadPoolExecutor 七个参数】
 * <ol>
 *   <li>corePoolSize=4：常驻线程数（即使空闲也不回收，低于 4 个任务不进队列直接开线程）；</li>
 *   <li>maximumPoolSize=8：线程上限（队列满了才继续开到 8，而不是先扩线程——注意这个顺序反直觉）；</li>
 *   <li>keepAliveTime=60s + TimeUnit：非核心线程（第 5~8 个）空闲 60s 后回收；</li>
 *   <li>workQueue：容量 64 的有界队列，排队等线程的任务，<b>必须给界</b>否则失去拒绝保护；</li>
 *   <li>threadFactory：统一命名 agent-loop-N（排查线程 dump 一眼定位），daemon=true 不阻碍 JVM 退出；</li>
 *   <li>handler：拒绝策略，见下。</li>
 * </ol>
 *
 * <p>【面试点：拒绝策略为什么选 AbortPolicy？】
 * 队列满且线程满时：AbortPolicy 抛 RejectedExecutionException，本类 catch 后给用户一句
 * "系统繁忙"的 SSE 错误事件——<b>快速失败 + 明确反馈</b>。DiscardPolicy 是静默丢任务（用户连接悬死）；
 * CallerRunsPolicy 会让 Tomcat 工作线程亲自跑几十秒的对话循环，拖垮整个容器的吞吐。
 */
@Slf4j
@RequiredArgsConstructor
@RestController
public class ChatController {

    public record ChatRequest(String sessionKey, @NotBlank String message) {
    }

    private final AgentRunner agentRunner;
    private final AgentSessionService sessionService;
    private final ObjectMapper objectMapper;

    /** 循环执行线程池：带初始化的 final 字段，不参与 @RequiredArgsConstructor 生成的构造器。 */
    private final ThreadPoolExecutor executor = newLoopExecutor();

    private static ThreadPoolExecutor newLoopExecutor() {
        AtomicInteger seq = new AtomicInteger();
        return new ThreadPoolExecutor(
                4, 8, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(64),
                r -> {
                    Thread thread = new Thread(r, "agent-loop-" + seq.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        // 不再接收新任务，等待在跑的对话循环收尾（daemon 线程兜底，不会卡住 JVM 退出）
        executor.shutdown();
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
            // AbortPolicy 拒绝（队列 64 + 线程 8 全满）：快速失败并给用户明确反馈
            log.warn("chat rejected, executor saturated, session {}", sessionKey);
            events.error("系统繁忙，请稍后重试");
            events.done();
            emitter.complete();
        }
        return emitter;
    }
}
