package io.github.renhaowan.platform.agent.memory.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.agent.memory.AgentSessionService;
import io.github.renhaowan.platform.agent.memory.ChatMessage;
import io.github.renhaowan.platform.agent.memory.ChatMessageMapper;
import io.github.renhaowan.platform.agent.memory.ChatSession;
import io.github.renhaowan.platform.agent.memory.ChatSessionMapper;
import io.github.renhaowan.platform.agent.memory.Checkpoint;
import io.github.renhaowan.platform.agent.memory.CheckpointMapper;
import jakarta.annotation.PreDestroy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** AgentSessionService 接口实现。 */
@RequiredArgsConstructor
@Service
public class AgentSessionServiceImpl implements AgentSessionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final CheckpointMapper checkpointMapper;

    @Override
    public String createSession(String title) {
        String sessionKey = java.util.UUID.randomUUID().toString();
        ChatSession session = new ChatSession();
        session.setSessionKey(sessionKey);
        session.setTitle(title);
        sessionMapper.insert(session);
        return sessionKey;
    }

    @Override
    public void appendMessage(String sessionKey, String role, String content, String toolName) {
        ChatMessage message = new ChatMessage();
        message.setSessionKey(sessionKey);
        message.setRole(role);
        message.setContent(content);
        message.setToolName(toolName);
        messageMapper.insert(message);
    }

    @Override
    public List<ChatMessage> loadHistory(String sessionKey) {
        return messageMapper.selectList(new QueryWrapper<ChatMessage>()
                .eq("session_key", sessionKey).orderByAsc("id").last("LIMIT 200"));
    }

    @Override
    public void saveCheckpoint(String sessionKey, String summary) {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.setSessionKey(sessionKey);
        checkpoint.setSummary(summary);
        checkpointMapper.insert(checkpoint);
    }

    @Override
    @PreDestroy
    public void shutdown() {
        // no-op，预留优雅关闭钩子
    }
}
