package io.github.renhaowan.platform.agent.memory;

import java.util.List;
import java.util.Optional;
import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import io.github.renhaowan.platform.agent.memory.entity.ChatSession;

/**
 * Agent 会话存储：消息历史按 sessionKey 聚合，写入即落库（服务重启不丢对话）。
 */
public interface AgentSessionService {

    /** 创建会话并绑定归属用户（user_id 决定续聊时的归属校验）。 */
    String createSession(String title, Long userId);

    void appendMessage(String sessionKey, String role, String content, String toolName);

    List<ChatMessage> loadHistory(String sessionKey);

    void saveCheckpoint(String sessionKey, String summary);

    /** 按 sessionKey 查会话（归属校验用）；不存在返回 empty。 */
    Optional<ChatSession> findBySessionKey(String sessionKey);

    void shutdown();
}
