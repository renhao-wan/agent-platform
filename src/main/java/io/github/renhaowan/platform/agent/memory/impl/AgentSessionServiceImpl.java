package io.github.renhaowan.platform.agent.memory.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.github.renhaowan.platform.agent.memory.AgentSessionService;
import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import io.github.renhaowan.platform.agent.memory.mapper.ChatMessageMapper;
import io.github.renhaowan.platform.agent.memory.entity.ChatSession;
import io.github.renhaowan.platform.agent.memory.mapper.ChatSessionMapper;
import io.github.renhaowan.platform.agent.memory.entity.Checkpoint;
import io.github.renhaowan.platform.agent.memory.entity.LlmUsage;
import io.github.renhaowan.platform.agent.memory.mapper.CheckpointMapper;
import io.github.renhaowan.platform.agent.memory.mapper.LlmUsageMapper;
import jakarta.annotation.PreDestroy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** AgentSessionService 接口实现。 */
@RequiredArgsConstructor
@Service
public class AgentSessionServiceImpl implements AgentSessionService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final CheckpointMapper checkpointMapper;
    private final LlmUsageMapper usageMapper;

    @Override
    public String createSession(Long userId) {
        String sessionKey = java.util.UUID.randomUUID().toString();
        ChatSession session = new ChatSession();
        session.setSessionKey(sessionKey);
        session.setUserId(userId);
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
    public UsageSummary usageOf(String sessionKey) {
        List<LlmUsage> rows = usageMapper.selectList(new QueryWrapper<LlmUsage>()
                .eq("session_key", sessionKey));
        int prompt = 0;
        int completion = 0;
        for (LlmUsage row : rows) {
            prompt += row.getPromptTokens() == null ? 0 : row.getPromptTokens();
            completion += row.getCompletionTokens() == null ? 0 : row.getCompletionTokens();
        }
        return new UsageSummary(prompt, completion, prompt + completion, rows.size());
    }

    @Override
    public List<SessionSummary> listSessions(Long userId) {
        if (userId == null) {
            return List.of();
        }
        List<ChatSession> sessions = sessionMapper.selectList(new QueryWrapper<ChatSession>()
                .eq("user_id", userId).orderByDesc("id").last("LIMIT 50"));
        if (sessions.isEmpty()) {
            return List.of();
        }

        // 标题取每个会话的首条用户消息：一次 IN 查询后在内存里分组，避免逐会话 N+1
        Map<String, String> titles = new HashMap<>();
        List<String> keys = sessions.stream().map(ChatSession::getSessionKey).toList();
        for (ChatMessage message : messageMapper.selectList(new QueryWrapper<ChatMessage>()
                .in("session_key", keys).eq("role", ChatMessage.ROLE_USER).orderByAsc("id"))) {
            titles.putIfAbsent(message.getSessionKey(), summarize(message.getContent()));
        }

        return sessions.stream()
                .map(session -> new SessionSummary(
                        session.getSessionKey(),
                        titles.getOrDefault(session.getSessionKey(), "新对话"),
                        session.getCreatedAt()))
                .toList();
    }

    /** 会话列表标题：压平空白并截断，完整内容仍在该会话的消息里。 */
    private static String summarize(String content) {
        String text = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return "新对话";
        }
        return text.length() <= 24 ? text : text.substring(0, 24) + "…";
    }

    @Override
    public void saveCheckpoint(String sessionKey, String summary) {
        Checkpoint checkpoint = new Checkpoint();
        checkpoint.setSessionKey(sessionKey);
        checkpoint.setSummary(summary);
        checkpointMapper.insert(checkpoint);
    }

    @Override
    public Optional<ChatSession> findBySessionKey(String sessionKey) {
        return Optional.ofNullable(sessionMapper.selectOne(
                new QueryWrapper<ChatSession>().eq("session_key", sessionKey)));
    }

    @Override
    public boolean ownsSession(String sessionKey, Long userId) {
        return findBySessionKey(sessionKey)
                .map(session -> session.getUserId() == null || session.getUserId().equals(userId))
                .orElse(true);
    }

    @Override
    @Transactional
    public boolean deleteSession(String sessionKey, Long userId) {
        ChatSession session = sessionMapper.selectOne(
                new QueryWrapper<ChatSession>().eq("session_key", sessionKey));
        if (session == null) {
            return false;
        }
        if (session.getUserId() != null && !session.getUserId().equals(userId)) {
            return false;
        }
        checkpointMapper.delete(new QueryWrapper<Checkpoint>().eq("session_key", sessionKey));
        messageMapper.delete(new QueryWrapper<ChatMessage>().eq("session_key", sessionKey));
        // 用业务键删除而不是 id：调用方拿到的就是 sessionKey，少一次主键转换
        sessionMapper.delete(new QueryWrapper<ChatSession>().eq("session_key", sessionKey));
        return true;
    }

    @Override
    @PreDestroy
    public void shutdown() {
        // no-op，预留优雅关闭钩子
    }
}
