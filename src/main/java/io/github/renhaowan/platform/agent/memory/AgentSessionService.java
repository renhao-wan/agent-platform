package io.github.renhaowan.platform.agent.memory;

import java.util.List;

/**
 * Agent 会话存储：消息历史按 sessionKey 聚合，写入即落库（服务重启不丢对话）。
 */
public interface AgentSessionService {

    String createSession(String title);

    void appendMessage(String sessionKey, String role, String content, String toolName);

    List<ChatMessage> loadHistory(String sessionKey);

    void saveCheckpoint(String sessionKey, String summary);

    void shutdown();
}
