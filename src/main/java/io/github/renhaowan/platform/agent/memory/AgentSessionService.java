package io.github.renhaowan.platform.agent.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import jakarta.annotation.PreDestroy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Agent 会话存储：消息历史按 sessionKey 聚合，写入即落库（服务重启不丢对话）。
 */
@RequiredArgsConstructor
@Service
public class AgentSessionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final CheckpointMapper checkpointMapper;

    public String createSession(String title) {
        String sessionKey = java.util.UUID.randomUUID().toString();
        ChatSession session = new ChatSession();
        session.setSessionKey(sessionKey);
        session.setTitle(title);
        sessionMapper.insert(session);
        return sessionKey;
    }

    public void appendMessage(String sessionKey, String role, String content, String toolName) {
        ChatMessage message = new ChatMessage();
        message.setSessionKey(sessionKey);
        message.setRole(role);
        message.setContent(content);
        message.setToolName(toolName);
        messageMapper.insert(message);
    }

    public List<ChatMessage> loadHistory(String sessionKey) {
        return messageMapper.selectList(new QueryWrapper<ChatMessage>()
                .eq("session_key", sessionKey).orderByAsc("id").last("LIMIT 200"));
    }

    public void saveCheckpoint(String sessionKey, String summary) {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.setSessionKey(sessionKey);
        checkpoint.setSummary(summary);
        checkpointMapper.insert(checkpoint);
    }

    @PreDestroy
    public void shutdown() {
        // no-op，预留优雅关闭钩子
    }
}
