package io.github.renhaowan.platform.agent.sse;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * 会话级执行锁：同一 sessionKey 同时只允许一个 ReAct 循环。
 * <p>
 * 两个请求并发续聊同一会话会交错写 chat_message、共享同一段上下文，
 * 模型看到的历史顺序与实际发生顺序不一致。这里直接拒绝后来者，
 * 而不是排队——排队会让用户对着一个"没反应"的输入框等待未知时长。
 * <p>
 * 单实例内存实现：锁随进程消失，与 ConfirmManager 的部署前提一致。
 */
@Component
public class SessionLockRegistry {

    private final Set<String> active = ConcurrentHashMap.newKeySet();

    /** @return true 表示抢到锁；false 表示该会话已有循环在跑 */
    public boolean tryAcquire(String sessionKey) {
        return active.add(sessionKey);
    }

    public void release(String sessionKey) {
        active.remove(sessionKey);
    }
}
