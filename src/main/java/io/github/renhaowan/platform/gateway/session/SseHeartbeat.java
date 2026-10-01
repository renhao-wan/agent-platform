package io.github.renhaowan.platform.gateway.session;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 心跳：25s 周期注释帧，防止中间层空闲断连；写失败即停止该连接的心跳。
 *
 * <p>【面试点】这里不用 Executors.newSingleThreadScheduledExecutor——它内部排队用的是
 * 无界队列且无法配置拒绝策略；显式 new ScheduledThreadPoolExecutor(1, factory) 至少把
 * 线程命名/daemon 收进 threadFactory。心跳任务数量恒等于 SSE 连接数、单条执行纳秒级，
 * 单线程足够；连接级失败通过 cancel 对应的 ScheduledFuture 停表，不污染线程池本身。
 */
@Component
public class SseHeartbeat {

    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, r -> {
        Thread thread = new Thread(r, "sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    public void start(SseEmitter emitter) {
        AtomicReference<ScheduledFuture<?>> ref = new AtomicReference<>();
        ScheduledFuture<?> future = scheduler.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keep-alive"));
            } catch (Exception e) {
                ScheduledFuture<?> sf = ref.get();
                if (sf != null) {
                    sf.cancel(false);
                }
            }
        }, 25, 25, TimeUnit.SECONDS);
        ref.set(future);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdownNow();
    }
}
