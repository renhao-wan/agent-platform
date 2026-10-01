package io.github.renhaowan.platform.gateway.session;

import io.github.renhaowan.platform.gateway.message.McpProtocol;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE 连接注册表：本实例持有的 SseEmitter 按 sessionKey 索引。
 * 消息回报优先本机直写；连接不在本实例时经 Redis PubSub 广播，
 * 由持有连接的实例（SessionEventSubscriber）接收并写出。
 */
@Slf4j
@Service
public class SseConnectionRegistry {

    public record OutboundMessage(String sessionKey, String json) {
    }

    /** 兼容保留：请优先引用 McpProtocol.CHANNEL_SESSION_EVENTS */
    public static final String TOPIC = McpProtocol.CHANNEL_SESSION_EVENTS;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final RTopic topic;

    public SseConnectionRegistry(RedissonClient redissonClient) {
        this.topic = redissonClient.getTopic(TOPIC);
    }

    public SseEmitter register(String sessionKey) {
        SseEmitter emitter = new SseEmitter(30 * 60_000L);
        emitters.put(sessionKey, emitter);
        emitter.onCompletion(() -> emitters.remove(sessionKey, emitter));
        emitter.onTimeout(() -> emitters.remove(sessionKey, emitter));
        emitter.onError(e -> emitters.remove(sessionKey, emitter));
        return emitter;
    }

    public boolean isLocal(String sessionKey) {
        return emitters.containsKey(sessionKey);
    }

    public void send(String sessionKey, String json) {
        SseEmitter emitter = emitters.get(sessionKey);
        if (emitter != null) {
            try {
                emitter.send(SseEmitter.event().data(json));
            } catch (Exception e) {
                emitters.remove(sessionKey, emitter);
                log.warn("local sse send failed, session {}: {}", sessionKey, e.getMessage());
            }
            return;
        }
        if (topic == null) {
            return;
        }
        try {
            topic.publishAsync(new OutboundMessage(sessionKey, json));
        } catch (Exception e) {
            log.warn("pubsub publish failed, session {}: {}", sessionKey, e.getMessage());
        }
    }

    /** PubSub 订阅回调：仅本实例持有连接时写出。 */
    public void onRemoteMessage(OutboundMessage message) {
        SseEmitter emitter = emitters.get(message.sessionKey());
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().data(message.json()));
        } catch (Exception e) {
            emitters.remove(message.sessionKey(), emitter);
        }
    }

    public void unregister(String sessionKey) {
        emitters.remove(sessionKey);
    }

    /** 结束并移除连接（会话终止时调用）。 */
    public void complete(String sessionKey) {
        SseEmitter emitter = emitters.remove(sessionKey);
        if (emitter != null) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
    }
}
