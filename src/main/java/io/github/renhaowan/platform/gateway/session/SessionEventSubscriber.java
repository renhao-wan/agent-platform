package io.github.renhaowan.platform.gateway.session;

import jakarta.annotation.PostConstruct;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.api.listener.MessageListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 订阅跨实例会话事件：其他实例发出的响应报文，若会话连接在本实例，则代为写出。
 */
@Component
public class SessionEventSubscriber {

    private static final Logger log = LoggerFactory.getLogger(SessionEventSubscriber.class);

    private final RedissonClient redissonClient;
    private final SseConnectionRegistry registry;

    public SessionEventSubscriber(RedissonClient redissonClient, SseConnectionRegistry registry) {
        this.redissonClient = redissonClient;
        this.registry = registry;
    }

    @PostConstruct
    public void subscribe() {
        RTopic topic = redissonClient.getTopic(SseConnectionRegistry.TOPIC);
        if (topic == null) {
            return;
        }
        topic.addListener(SseConnectionRegistry.OutboundMessage.class,
                (MessageListener<SseConnectionRegistry.OutboundMessage>) (channel, msg) -> registry.onRemoteMessage(msg));
        log.info("subscribed session event topic {}", SseConnectionRegistry.TOPIC);
    }
}
