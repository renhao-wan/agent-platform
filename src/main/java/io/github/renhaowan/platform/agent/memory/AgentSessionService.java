package io.github.renhaowan.platform.agent.memory;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import io.github.renhaowan.platform.agent.memory.entity.ChatMessage;
import io.github.renhaowan.platform.agent.memory.entity.ChatSession;

/**
 * Agent 会话存储：消息历史按 sessionKey 聚合，写入即落库（服务重启不丢对话）。
 */
public interface AgentSessionService {

    /** 会话列表项：标题取自首条用户消息，供控制台历史列表展示。 */
    record SessionSummary(String sessionKey, String title, LocalDateTime createdAt) {
    }

    /** 会话累计 token 用量；totalTokens 含 prompt 与 completion。 */
    record UsageSummary(int promptTokens, int completionTokens, int totalTokens, int calls) {
    }

    /** 创建会话并绑定归属用户（user_id 决定续聊时的归属校验）。 */
    String createSession(Long userId);

    /** 当前用户的会话，按创建时间倒序（最多 50 条）。 */
    List<SessionSummary> listSessions(Long userId);

    void appendMessage(String sessionKey, String role, String content, String toolName);

    List<ChatMessage> loadHistory(String sessionKey);

    /** 汇总该会话的 LLM 用量。会话不存在时返回全 0，不产生额外查询口径。 */
    UsageSummary usageOf(String sessionKey);

    void saveCheckpoint(String sessionKey, String summary);

    /** 按 sessionKey 查会话（归属校验用）；不存在返回 empty。 */
    Optional<ChatSession> findBySessionKey(String sessionKey);

    /**
     * 续聊/读取归属校验：会话存在且已绑定 user_id 时，仅归属者可访问；
     * user_id 为 NULL 的历史遗留会话放行（不追溯封锁存量数据）。
     */
    boolean ownsSession(String sessionKey, Long userId);

    /**
     * 删除会话及其消息、checkpoint。仅归属者可删（无主遗留会话同样按归属校验放行）；
     * 会话不存在或不属于该用户时返回 false，不产生任何写入。
     */
    boolean deleteSession(String sessionKey, Long userId);

    void shutdown();
}
