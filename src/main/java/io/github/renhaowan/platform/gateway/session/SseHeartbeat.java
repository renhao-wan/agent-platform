package io.github.renhaowan.platform.gateway.session;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 心跳：25s 周期注释帧，防止中间层空闲断连；写失败即停止该连接的心跳。
 */
@Component
public class SseHeartbeat {

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "sse-heartbeat");
        t.setDaemon(true);
        return t;
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
